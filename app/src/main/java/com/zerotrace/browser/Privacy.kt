package com.zerotrace.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig
import java.security.SecureRandom

/** Locks down a WebView so it gives websites as little as possible. */
object Privacy {

    /** New random value per process = per session / per new identity. */
    private val sessionSeed: Int = SecureRandom().nextInt()

    private var shimCache: String? = null

    private fun shim(context: Context): String =
        shimCache ?: context.assets.open("privacy_shim.js").bufferedReader().use { it.readText() }
            .replace("__SEED__", sessionSeed.toString())
            .also { shimCache = it }

    private fun chromeMajor(context: Context): String =
        WebViewCompat.getCurrentWebViewPackage(context)?.versionName
            ?.substringBefore('.')?.takeIf { it.all(Char::isDigit) } ?: "130"

    /**
     * Exactly the "reduced" user agent every Chrome on Android sends: no device
     * model, no real Android version, no "wv" WebView marker.
     */
    fun userAgent(context: Context): String {
        val v = chromeMajor(context)
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$v.0.0.0 Mobile Safari/537.36"
    }

    /** True when the page-level protections (WebRTC kill switch etc.) can be installed. */
    fun canInjectShim(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun configure(context: Context, webView: WebView) {
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        webView.isSaveEnabled = false

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true // lives only for this session, wiped on exit
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.setGeolocationEnabled(false)
        @Suppress("DEPRECATION")
        s.saveFormData = false
        @Suppress("DEPRECATION")
        s.savePassword = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.mediaPlaybackRequiresUserGesture = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.userAgentString = userAgent(context)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)

        // Google Safe Browsing sends the sites you visit to Google.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, false)
        }
        // "X-Requested-With: com.zerotrace.browser" would tell every site which app you use.
        // Current WebViews no longer send it by default; this also covers older ones
        // (it throws on WebViews that don't support the setting, which is fine).
        runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet()) }
        // Client-hint headers: look like generic Chrome, hide phone model & Android version.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val v = chromeMajor(context)
            fun brand(name: String, major: String) = UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name).setMajorVersion(major).setFullVersion("$major.0.0.0").build()
            WebSettingsCompat.setUserAgentMetadata(
                s,
                UserAgentMetadata.Builder()
                    .setBrandVersionList(
                        listOf(brand("Not)A;Brand", "99"), brand("Google Chrome", v), brand("Chromium", v))
                    )
                    .setFullVersion("$v.0.0.0")
                    .setPlatform("Android")
                    .setPlatformVersion("10.0.0")
                    .setModel("")
                    .setArchitecture("")
                    .setBitness(UserAgentMetadata.BITNESS_DEFAULT)
                    .setMobile(true)
                    .setWow64(false)
                    .build()
            )
        }
        // Media Integrity would let sites ask Google Play to vouch for this exact device.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS)) {
            WebSettingsCompat.setWebViewMediaIntegrityApiStatus(
                s,
                WebViewMediaIntegrityApiStatusConfig.Builder(
                    WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_DISABLED
                ).build()
            )
        }
        // Ad attribution reporting links web activity to apps on the phone.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
            WebSettingsCompat.setAttributionRegistrationBehavior(s, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST)) {
            WebSettingsCompat.setPaymentRequestEnabled(s, false)
        }

        // Page-level protections: WebRTC off, fixed time zone/language/hardware values,
        // canvas/WebGL/audio noise. Runs before any site script, in every frame.
        if (canInjectShim()) {
            WebViewCompat.addDocumentStartJavaScript(webView, shim(context), setOf("*"))
        }
    }
}
