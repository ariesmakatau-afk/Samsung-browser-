package com.zerotrace.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity(), TorController.Listener {

    private class Tab(val webView: WebView) {
        var title: String = "New tab"
        var url: String = ""
        var progress: Int = 100
        /** Host of the page shown in this tab; used to tell first- from third-party requests. */
        @Volatile var pageHost: String? = null
        /** Every request this tab made, kept in memory only (never written to disk). */
        val connections = ArrayDeque<Connection>()
        var blockedCount = 0

        @Synchronized fun record(c: Connection) {
            if (c.blocked) blockedCount++
            connections.addLast(c)
            while (connections.size > MAX_LOG) connections.removeFirst()
        }

        @Synchronized fun snapshot(): List<Connection> = connections.toList()
    }

    private class Connection(val url: String, val method: String, val blocked: Boolean, val mainFrame: Boolean)

    /** Lookup for the WebView callbacks that run on background threads. */
    private val tabByView = ConcurrentHashMap<WebView, Tab>()

    private lateinit var address: EditText
    private lateinit var tabCount: TextView
    private lateinit var progress: ProgressBar
    private lateinit var webContainer: FrameLayout
    private lateinit var torOverlay: View
    private lateinit var torStatus: TextView
    private lateinit var torBadge: TextView

    private val tabs = mutableListOf<Tab>()
    private var current = -1
    private var tor: TorController? = null
    private var browsingAllowed = false
    private var wiping = false
    private var lastBackPress = 0L
    private val pendingUrls = ArrayList<String>()
    private var startPage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank thumbnail in the Recents screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        address = findViewById(R.id.address)
        tabCount = findViewById(R.id.tabCount)
        progress = findViewById(R.id.progress)
        webContainer = findViewById(R.id.webContainer)
        torOverlay = findViewById(R.id.torOverlay)
        torStatus = findViewById(R.id.torStatus)
        torBadge = findViewById(R.id.torBadge)
        address.isSaveEnabled = false

        address.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(address.text.toString())
                true
            } else false
        }
        address.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) updateChrome() }
        tabCount.setOnClickListener { showTabs() }
        findViewById<View>(R.id.menuButton).setOnClickListener { showMenu(it) }
        // One tap: erase everything and close.
        findViewById<View>(R.id.burnButton).setOnClickListener { exitAndWipe() }
        TrackerBlocker.load(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        // Watches for "swipe away from Recents" so we can wipe on that too.
        startService(Intent(this, WipeWatcherService::class.java))

        queueIntentUrl(intent)

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            fatal(
                "Your Android System WebView is too old to route traffic through Tor safely.\n\n" +
                    "Update \"Android System WebView\" (or Chrome) from the Play Store / Galaxy Store, then reopen."
            )
            return
        }
        if (!Privacy.canInjectShim()) {
            fatal(
                "Your Android System WebView is too old to block WebRTC IP leaks.\n\n" +
                    "Update \"Android System WebView\" (or Chrome), then reopen."
            )
            return
        }
        tor = TorController(applicationContext, this).also { it.start() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        queueIntentUrl(intent)
        if (browsingAllowed) flushPendingUrls()
    }

    private fun queueIntentUrl(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && (data.scheme == "http" || data.scheme == "https")) {
            pendingUrls.add(data.toString())
        }
        intent.data = null
    }

    // ---------------------------------------------------------------- Tor

    override fun onTorProgress(percent: Int, summary: String) {
        torStatus.text = getString(R.string.connecting) + " $percent%\n$summary"
    }

    override fun onTorError(message: String) {
        torStatus.text = "Tor error: $message\n\nClose the browser and try again."
    }

    override fun onTorReady(socksPort: Int) {
        // Every request from every tab goes to Tor's SOCKS5 port. Hostnames are resolved
        // by Tor (no DNS leak). There is no "direct" fallback: if Tor is down, nothing loads.
        val config = ProxyConfig.Builder()
            .addProxyRule("socks5://127.0.0.1:$socksPort")
            .removeImplicitRules()
            .build()
        try {
            ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(this)) {
                browsingAllowed = true
                torOverlay.visibility = View.GONE
                torBadge.setTextColor(ContextCompat.getColor(this, R.color.accent))
                if (tabs.isEmpty() && pendingUrls.isEmpty()) newTab(null)
                flushPendingUrls()
            }
        } catch (e: Exception) {
            fatal("Could not route traffic through Tor: ${e.message}")
        }
    }

    private fun flushPendingUrls() {
        val urls = ArrayList(pendingUrls)
        pendingUrls.clear()
        urls.forEach { newTab(it) }
    }

    private fun fatal(message: String) {
        browsingAllowed = false
        torOverlay.visibility = View.VISIBLE
        torStatus.text = message
    }

    // ---------------------------------------------------------------- Tabs

    private fun newTab(url: String?) {
        if (!browsingAllowed) return
        val tab = Tab(createWebView())
        tabByView[tab.webView] = tab
        tabs.add(tab)
        switchTo(tabs.lastIndex)
        if (url != null) load(tab, url) else showStartPage(tab)
    }

    private fun switchTo(index: Int) {
        currentTab()?.webView?.onPause()
        webContainer.removeAllViews()
        current = index
        val tab = tabs[index]
        webContainer.addView(
            tab.webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        tab.webView.onResume()
        updateChrome()
    }

    private fun closeTab(index: Int) {
        val tab = tabs.removeAt(index)
        tabByView.remove(tab.webView)
        if (index == current) webContainer.removeAllViews()
        destroyWebView(tab.webView)
        when {
            tabs.isEmpty() -> { current = -1; newTab(null) }
            index < current -> { current--; updateChrome() }
            index == current -> switchTo(minOf(index, tabs.lastIndex))
            else -> updateChrome()
        }
    }

    private fun currentTab(): Tab? = tabs.getOrNull(current)

    private fun tabFor(view: WebView): Tab? = tabs.firstOrNull { it.webView === view }

    private fun destroyWebView(wv: WebView) {
        runCatching {
            wv.stopLoading()
            wv.clearHistory()
            wv.clearCache(true)
            wv.clearFormData()
            wv.removeAllViews()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    // ---------------------------------------------------------------- Navigation

    private fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = toUrl(text) ?: return
        hideKeyboard()
        val tab = currentTab()
        if (tab == null) newTab(url) else load(tab, url)
    }

    private fun toUrl(text: String): String? {
        val lower = text.lowercase()
        if (lower.startsWith("https://")) return text
        if (lower.startsWith("http://")) return upgrade(Uri.parse(text)).toString()
        val looksLikeHost = !text.contains(' ') && text.contains('.') && !text.endsWith('.')
        if (looksLikeHost) return upgrade(Uri.parse("https://$text")).toString()
        val url = Settings.searchEngine(this).urlFor(text)
        if (url == null) {
            Toast.makeText(this, "Search is turned off. Type a web address, or pick a search engine in ⋮.", Toast.LENGTH_LONG).show()
        }
        return url
    }

    /** Plain http is readable by the Tor exit relay, so always use https (except .onion). */
    private fun upgrade(uri: Uri): Uri {
        val host = uri.host ?: return uri
        if (uri.scheme == "http" && !host.endsWith(".onion")) return uri.buildUpon().scheme("https").build()
        return uri
    }

    private fun load(tab: Tab, url: String) {
        tab.url = url
        tab.webView.loadUrl(url)
        updateChrome()
    }

    private fun showStartPage(tab: Tab) {
        val html = startPage ?: assets.open("start.html").bufferedReader().use { it.readText() }.also { startPage = it }
        tab.url = ""
        tab.title = "New tab"
        tab.webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        updateChrome()
    }

    private fun isInternalUrl(url: String?) =
        url.isNullOrEmpty() || url == "about:blank" || url.startsWith("data:")

    private fun handleBack() {
        val tab = currentTab()
        when {
            tab != null && tab.webView.canGoBack() -> tab.webView.goBack()
            tabs.size > 1 -> closeTab(current)
            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPress < 2500) {
                    exitAndWipe()
                } else {
                    lastBackPress = now
                    Toast.makeText(this, "Press back again to close and erase everything", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        Privacy.configure(this, wv)
        wv.webViewClient = client
        wv.webChromeClient = chromeClient
        wv.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(
                this,
                "Downloads are disabled: files stay on your phone and can leak your real IP when opened.",
                Toast.LENGTH_LONG
            ).show()
        }
        return wv
    }

    private val client = object : WebViewClient() {
        // Runs on a background thread for every request the page makes. Used to block
        // third-party trackers and to keep the per-tab connection log (memory only).
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val tab = tabByView[view] ?: return null
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            if (request.isForMainFrame) tab.pageHost = host
            val blocked = !request.isForMainFrame && TrackerBlocker.shouldBlock(host, tab.pageHost)
            tab.record(Connection(uri.toString(), request.method ?: "GET", blocked, request.isForMainFrame))
            return if (blocked) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "https", "about", "data", "blob" -> false
                "http" -> {
                    val safe = upgrade(uri)
                    if (safe != uri) { view.loadUrl(safe.toString()); true } else false
                }
                else -> {
                    // intent:, tel:, mailto:, market:, app links… would hand you to another
                    // app outside Tor and could reveal who you are.
                    Toast.makeText(
                        this@MainActivity,
                        "Blocked: opening other apps from here could reveal your identity.",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            val tab = tabFor(view) ?: return
            tab.url = if (isInternalUrl(url)) "" else url.orEmpty()
            tab.progress = 0
            if (tab === currentTab()) updateChrome()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val tab = tabFor(view) ?: return
            tab.url = if (isInternalUrl(url)) "" else url.orEmpty()
            tab.progress = 100
            view.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
            if (tab === currentTab()) updateChrome()
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            // Never click through certificate errors: over Tor that's exactly what a
            // malicious exit relay would try.
            handler.cancel()
            Toast.makeText(this@MainActivity, "Blocked: this site's security certificate is not valid.", Toast.LENGTH_LONG).show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val index = tabs.indexOfFirst { it.webView === view }
            if (index >= 0) closeTab(index) else destroyWebView(view)
            return true
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            val tab = tabFor(view) ?: return
            tab.progress = newProgress
            if (tab === currentTab()) updateProgress(tab)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val tab = tabFor(view) ?: return
            if (!title.isNullOrBlank()) tab.title = title
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny() // camera, microphone, protected media IDs, MIDI…
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            // Uploaded photos/documents often carry GPS coordinates and device info.
            filePathCallback.onReceiveValue(null)
            Toast.makeText(this@MainActivity, "File uploads are disabled for your privacy.", Toast.LENGTH_SHORT).show()
            return true
        }
    }

    // ---------------------------------------------------------------- UI

    private fun updateChrome() {
        tabCount.text = tabs.size.toString()
        val tab = currentTab() ?: return
        if (!address.hasFocus()) address.setText(tab.url)
        updateProgress(tab)
    }

    private fun updateProgress(tab: Tab) {
        progress.visibility = if (tab.progress in 0..99) View.VISIBLE else View.GONE
        progress.progress = tab.progress
    }

    private fun hideKeyboard() {
        address.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        currentTab()?.webView?.requestFocus()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        m.add(0, 1, 0, getString(R.string.forward)).isEnabled = currentTab()?.webView?.canGoForward() == true
        m.add(0, 2, 1, getString(R.string.reload))
        m.add(0, 3, 2, getString(R.string.new_tab))
        val tab = currentTab()
        m.add(0, 6, 3, "Connections (${tab?.snapshot()?.size ?: 0} requests, ${tab?.blockedCount ?: 0} trackers blocked)")
        m.add(0, 7, 4, "Search engine: ${Settings.searchEngine(this).label}")
        m.add(0, 4, 5, getString(R.string.new_identity))
        m.add(0, 5, 6, getString(R.string.exit_wipe))
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> currentTab()?.webView?.goForward()
                2 -> currentTab()?.webView?.reload()
                3 -> newTab(null)
                4 -> confirm(
                    "New identity",
                    "Closes all tabs, erases everything and reconnects to Tor with new circuits and a new fingerprint."
                ) { wipe(restart = true) }
                5 -> exitAndWipe()
                6 -> showConnections()
                7 -> chooseSearchEngine()
            }
            true
        }
        popup.show()
    }

    private fun showConnections() {
        val tab = currentTab() ?: return
        val log = tab.snapshot().asReversed()
        val lines = log.map { c ->
            val tag = when {
                c.blocked -> "✕ BLOCKED  "
                c.mainFrame -> "▶ PAGE  "
                else -> "→ ${c.method}  "
            }
            tag + c.url.take(200)
        }.ifEmpty { listOf("No requests yet.") }
        AlertDialog.Builder(this)
            .setTitle("Connections in this tab")
            .setMessage(
                "Every request below went through Tor, or was blocked before leaving the phone. " +
                    "Newest first. This list lives in memory only and is erased with everything else."
            )
            .setItems(lines.toTypedArray(), null)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun chooseSearchEngine() {
        val engines = SearchEngine.entries
        val selected = engines.indexOf(Settings.searchEngine(this))
        AlertDialog.Builder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(engines.map { it.label }.toTypedArray(), selected) { d, which ->
                Settings.setSearchEngine(this, engines[which])
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTabs() {
        if (!browsingAllowed) return
        lateinit var dialog: AlertDialog
        val adapter = object : BaseAdapter() {
            override fun getCount() = tabs.size
            override fun getItem(position: Int) = tabs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
                val tab = tabs[position]
                v.findViewById<TextView>(R.id.tabTitle).apply {
                    text = if (position == current) "● ${tab.title}" else tab.title
                }
                v.findViewById<TextView>(R.id.tabUrl).text = tab.url.ifEmpty { "Start page" }
                v.findViewById<View>(R.id.tabClose).setOnClickListener {
                    closeTab(position)
                    notifyDataSetChanged()
                }
                return v
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.tabs))
            .setAdapter(adapter) { _, which -> switchTo(which) }
            .setPositiveButton(getString(R.string.new_tab)) { _, _ -> newTab(null) }
            .setNeutralButton("Close all") { _, _ ->
                while (tabs.size > 1) closeTab(tabs.lastIndex)
                closeTab(0)
            }
            .create()
        dialog.show()
    }

    // ---------------------------------------------------------------- Wipe

    private fun exitAndWipe() = wipe(restart = false)

    private fun wipe(restart: Boolean) {
        if (wiping) return
        wiping = true
        browsingAllowed = false
        webContainer.removeAllViews()
        tabs.forEach { destroyWebView(it.webView) }
        tabs.clear()
        tabByView.clear()
        tor?.stop()
        Wiper.wipeAndExit(this, this, restart)
    }

    override fun onPause() {
        super.onPause()
        currentTab()?.webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        currentTab()?.webView?.onResume()
    }

    override fun onDestroy() {
        if (isFinishing && !wiping) {
            wiping = true
            tabs.forEach { destroyWebView(it.webView) }
            tabs.clear()
            tor?.stop()
            super.onDestroy()
            Wiper.wipeAndExit(this, null, restart = false)
            return
        }
        super.onDestroy()
    }
}

private const val MAX_LOG = 1000
