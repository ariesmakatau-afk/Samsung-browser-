package com.zerotrace.browser

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Exists only to be told when the user swipes the browser away from the Recents
 * screen, which is how most people "close" an app on Android. When that happens,
 * everything is wiped and the process is killed.
 */
class WipeWatcherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        Wiper.wipeAndExit(this, activity = null, restart = false)
    }
}
