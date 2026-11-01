package org.blueFrontier.devSideCar.next.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bookmarks and a global visit log, both small enough to live in shared
 * preferences: a browser this size has no business carrying a database.
 */
object BrowserData {

    class Entry(val url: String, val title: String)

    private const val PREFS = "ds-browser"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 300

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): MutableList<Entry> {
        val raw = prefs(context).getString(key, null) ?: return mutableListOf()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val out = mutableListOf<Entry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isNotBlank()) out.add(Entry(url, item.optString("title")))
        }
        return out
    }

    private fun write(context: Context, key: String, entries: List<Entry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().put("url", entry.url).put("title", entry.title))
        }
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    fun bookmarks(context: Context): MutableList<Entry> = read(context, KEY_BOOKMARKS)

    fun isBookmarked(context: Context, url: String): Boolean =
        bookmarks(context).any { it.url == url }

    /** Adds or removes url; returns true when it is now bookmarked. */
    fun toggleBookmark(context: Context, url: String, title: String): Boolean {
        val entries = bookmarks(context)
        val existing = entries.firstOrNull { it.url == url }
        return if (existing != null) {
            entries.remove(existing)
            write(context, KEY_BOOKMARKS, entries)
            false
        } else {
            entries.add(0, Entry(url, title))
            write(context, KEY_BOOKMARKS, entries)
            true
        }
    }

    fun history(context: Context): MutableList<Entry> = read(context, KEY_HISTORY)

    fun addHistory(context: Context, url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val entries = history(context)
        // One row per URL: revisiting moves it to the top instead of piling up.
        entries.removeAll { it.url == url }
        entries.add(0, Entry(url, title))
        while (entries.size > MAX_HISTORY) entries.removeAt(entries.size - 1)
        write(context, KEY_HISTORY, entries)
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }
}


