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
class TorController(private val context: Context, private val listener: Listener) {

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
            """.trimIndent() + "\n"
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

    fun stop() {
        stopped = true
        runCatching { LocalBroadcastManager.getInstance(context).unregisterReceiver(errorReceiver) }
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        runCatching { context.stopService(Intent(context, TorService::class.java)) }
    }
}
