package com.zerotrace.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import kotlin.system.exitProcess

/**
 * Runs in its own process (":restart"). Kills the old browser process, which has
 * already wiped itself, then launches a completely fresh browser: new process,
 * new Tor instance, new circuits, new WebView profile, new fingerprint noise.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        startActivity(
            Intent(this, MainActivity::class.java)
                // In memory only: which Tor exits the old identity used, so they're avoided.
                .putStringArrayListExtra(
                    MainActivity.EXTRA_EXCLUDE_EXITS,
                    intent.getStringArrayListExtra(MainActivity.EXTRA_EXCLUDE_EXITS) ?: ArrayList()
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    companion object {
        const val EXTRA_PID = "pid"
    }
}
