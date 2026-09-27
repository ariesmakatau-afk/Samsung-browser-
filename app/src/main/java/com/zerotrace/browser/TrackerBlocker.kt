package com.zerotrace.browser

import android.content.Context

/**
 * Blocks requests to known third-party tracking, analytics and ad domains.
 * The list ships inside the app (assets/trackers.txt) and is never updated from
 * the network, so there is no remote configuration.
 */
object TrackerBlocker {

    @Volatile private var domains: Set<String> = emptySet()

    fun load(context: Context) {
        domains = context.assets.open("trackers.txt").bufferedReader().useLines { lines ->
            lines.map { it.substringBefore('#').trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        }
    }

    /** Returns the list entry that [host] falls under, or null. */
    private fun match(host: String): String? {
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in domains) return h
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
        }
    }

    /**
     * True if [requestHost] is a tracker and is being loaded as a third party, i.e.
     * the page you're on ([pageHost]) is not that tracker's own site.
     */
    fun shouldBlock(requestHost: String, pageHost: String?): Boolean {
        val entry = match(requestHost) ?: return false
        if (pageHost != null && (pageHost == entry || pageHost.endsWith(".$entry"))) return false
        return true
    }
}
