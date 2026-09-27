package com.zerotrace.browser

import android.app.Application
import android.os.Build
import android.os.LocaleList
import android.webkit.WebView
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.TimeZone

class ZeroApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The restart helper runs in its own process and must not touch anything.
        if (!isMainProcess()) return

        // Anything left over from a previous run (crash, force-stop, battery died,
        // phone rebooted) is destroyed before a single byte of new state is written.
        Wiper.wipeDisk(this)

        // Don't let the phone's language or time zone reach websites.
        Locale.setDefault(Locale.US)
        LocaleList.setDefault(LocaleList(Locale.US))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        // A brand-new, randomly named WebView profile for every session, so even a
        // file that somehow survived deletion can never be picked up again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            WebView.setDataDirectorySuffix("s" + bytes.joinToString("") { "%02x".format(it) })
        }
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrNull()
        return name == null || name == packageName
    }
}
