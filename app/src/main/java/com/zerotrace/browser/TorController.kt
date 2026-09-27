package com.zerotrace.browser

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.torproject.jni.TorService

/**
 * Runs an embedded Tor client inside the app and reports when its SOCKS port is
 * ready. All web traffic is sent through it, so websites only ever see the IP
 * address of a random Tor exit relay, never yours.
 */
class TorController(
    private val context: Context,
    private val listener: Listener,
    /** Exit relays (and their /16 networks) used by the previous identity. Never reused. */
    private val excludeExits: List<String> = emptyList(),
) {

    interface Listener {
        fun onTorProgress(percent: Int, summary: String)
        fun onTorReady(socksPort: Int)
        fun onTorError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var service: TorService? = null
    @Volatile private var stopped = false
    @Volatile private var ready = false
    private var bound = false

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val msg = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor failed to start"
            listener.onTorError(msg)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TorService.LocalBinder).service
            startPolling()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun start() {
        writeTorrc()
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(errorReceiver, IntentFilter(TorService.ACTION_ERROR))
        bound = context.bindService(
            Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        if (!bound) listener.onTorError("Could not start the Tor service")
    }

    private fun writeTorrc() {
        TorService.getTorrc(context).writeText(
            """
            # Client only, never a relay.
            ClientOnly 1
            # Keep Tor's own disk writes to a minimum (they are wiped on exit anyway).
            AvoidDiskWrites 1
            # Refuse any SOCKS request that would resolve DNS locally (DNS leak guard).
            SafeSocks 1
            TestSocks 0
            # Every website gets its own circuit, so trackers on different sites
            # can't link your visits by exit IP.
            SocksPort auto IsolateDestAddr
            """.trimIndent() + "\n" +
                // After "New identity", never leave through the exits the previous identity
                // used (or their networks), so the site can't see the same IP for both accounts.
                (if (excludeExits.isNotEmpty()) "ExcludeExitNodes ${excludeExits.joinToString(",")}\n" else "")
        )
    }

    private fun startPolling() {
        Thread({
            val phase = Regex("""PROGRESS=(\d+).*?SUMMARY="([^"]*)"""")
            while (!stopped && !ready) {
                val s = service
                if (s != null) {
                    val info = runCatching { s.getInfo("status/bootstrap-phase") }.getOrNull()
                    val m = info?.let { phase.find(it) }
                    val percent = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val summary = m?.groupValues?.get(2) ?: "Starting Tor"
                    val port = runCatching { s.socksPort }.getOrDefault(0)
                    if (percent >= 100 && port > 0) {
                        ready = true
                        main.post { if (!stopped) listener.onTorReady(port) }
                        break
                    }
                    main.post { if (!stopped) listener.onTorProgress(percent, summary) }
                }
                Thread.sleep(600)
            }
        }, "tor-bootstrap").apply { isDaemon = true }.start()
    }

    /**
     * Exit relays of all circuits this Tor instance has built, as "\$FINGERPRINT" plus
     * their "a.b.0.0/16" network. Kept in memory only and handed to the next identity.
     */
    fun recentExits(): ArrayList<String> {
        val out = ArrayList<String>()
        val s = service ?: return out
        val result = arrayOf<List<String>>(emptyList())
        val t = Thread {
            val status = runCatching { s.getInfo("circuit-status") }.getOrNull().orEmpty()
            val fps = status.lineSequence()
                .mapNotNull { it.trim().split(' ').getOrNull(2)?.split(',')?.lastOrNull() }
                .map { it.substringBefore('~').substringBefore('=') }
                .filter { FINGERPRINT.matches(it) }
                .toSet()
            val list = ArrayList<String>(fps)
            for (fp in fps) {
                val ns = runCatching { s.getInfo("ns/id/${fp.removePrefix("$")}") }.getOrNull() ?: continue
                val ip = ns.lineSequence().firstOrNull { it.startsWith("r ") }?.split(' ')?.getOrNull(6) ?: continue
                val parts = ip.split('.')
                if (parts.size == 4) list.add("${parts[0]}.${parts[1]}.0.0/16")
            }
            result[0] = list
        }
        t.start()
        t.join(3000)
        out.addAll(result[0].distinct().take(200))
        return out
    }

    fun stop() {
        stopped = true
        runCatching { LocalBroadcastManager.getInstance(context).unregisterReceiver(errorReceiver) }
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        runCatching { context.stopService(Intent(context, TorService::class.java)) }
    }
}

private val FINGERPRINT = Regex("""\$[0-9A-Fa-f]{40}""")
