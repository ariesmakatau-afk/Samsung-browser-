package com.zerotrace.browser

import android.content.Context
import java.net.URLEncoder

enum class SearchEngine(val label: String, private val template: String?) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    DUCKDUCKGO_ONION(
        "DuckDuckGo onion (never leaves Tor)",
        "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/?q=%s"
    ),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    MOJEEK("Mojeek", "https://www.mojeek.com/search?q=%s"),
    NONE("None: only open web addresses I type", null);

    /** URL for [query], or null when searching is turned off. */
    fun urlFor(query: String): String? =
        template?.replace("%s", URLEncoder.encode(query, "UTF-8"))
}

/**
 * The app's only persistent setting: which search engine to use. It's a choice,
 * not browsing data, so it's the one thing the wipe keeps. Nothing else is stored.
 */
object Settings {
    const val PREFS_NAME = "zt_settings"
    private const val KEY_SEARCH = "search_engine"

    fun searchEngine(context: Context): SearchEngine {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SEARCH, null)
        return SearchEngine.entries.firstOrNull { it.name == name } ?: SearchEngine.DUCKDUCKGO
    }

    fun setSearchEngine(context: Context, engine: SearchEngine) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SEARCH, engine.name).commit()
    }
}
