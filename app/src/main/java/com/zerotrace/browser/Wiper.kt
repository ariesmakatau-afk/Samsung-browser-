package com.zerotrace.browser

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import org.torproject.jni.TorService
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/** Destroys every trace this app can leave on the device. */
object Wiper {

    /** Directories under the app's data dir that must never be touched. */
    private val KEEP = setOf(
        "lib",        // symlink to the installed native libraries (read-only)
        "code_cache", // compiled app code, contains no browsing data
    )

    /**
     * Deletes everything the app has written to disk: WebView profile (cookies, local
     * storage, IndexedDB, service workers, HTTP cache, history), Tor state, prefs,
     * databases, files and caches. Only safe while no WebView / Tor is running, i.e.
     * at process start or right before the process is killed.
     */
    fun wipeDisk(context: Context) {
        val dataDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.dataDir
        else context.filesDir.parentFile
        dataDir?.listFiles()?.forEach { f ->
            when {
                f.name in KEEP -> Unit
                // Keep only the search-engine choice (see Settings); delete every other pref file.
                f.name == "shared_prefs" -> f.listFiles()?.forEach {
                    if (it.name != "${Settings.PREFS_NAME}.xml") deleteTree(it)
                }
                else -> deleteTree(f)
            }
        }
        runCatching { context.externalCacheDirs?.forEach { dir -> dir?.let(::deleteTree) } }
        runCatching { context.getExternalFilesDirs(null)?.forEach { dir -> dir?.let(::deleteTree) } }
    }

    private fun deleteTree(f: File) {
        runCatching {
            if (Files.isSymbolicLink(f.toPath())) {
                f.delete()
                return
            }
            if (f.isDirectory) f.listFiles()?.forEach(::deleteTree)
            f.delete()
        }
    }

    /** Clears WebView's in-memory and on-disk stores through its own APIs. */
    fun wipeWebViewStores(context: Context) {
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                removeSessionCookies(null)
                flush()
            }
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching { GeolocationPermissions.getInstance().clearAll() }
        runCatching {
            @Suppress("DEPRECATION")
            WebViewDatabase.getInstance(context).apply {
                clearHttpAuthUsernamePassword()
                clearFormData()
            }
        }
    }

    private fun clearClipboard(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
            else cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
    }

    /**
     * Wipes everything, shuts Tor down and kills the process, so nothing survives in
     * memory either. With [restart] the browser comes straight back as a brand-new
     * identity (new Tor circuits, new profile, new fingerprint noise).
     */
    fun wipeAndExit(context: Context, activity: Activity?, restart: Boolean) {
        val app = context.applicationContext
        wipeWebViewStores(app)
        clearClipboard(app)
        runCatching { app.stopService(Intent(app, TorService::class.java)) }
        runCatching { app.stopService(Intent(app, WipeWatcherService::class.java)) }
        wipeDisk(app)
        if (restart) {
            // A helper in a separate process kills this one and starts a fresh browser.
            runCatching {
                app.startActivity(
                    Intent(app, RestartActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(RestartActivity.EXTRA_PID, Process.myPid())
                )
                activity?.finishAndRemoveTask()
                return
            }
        }
        activity?.finishAndRemoveTask()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
