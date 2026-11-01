package org.blueFrontier.devSideCar.next.android

import android.content.Context

/**
 * Browser-side preferences shared by the start screen and Settings: the home
 * page the ⌂ button and the first launch use.
 */
object BrowserPrefs {

    private const val FILE = "ds-app"
    private const val KEY_HOME = "homeUrl"

    /**
     * Google's page script sends embedded WebViews to its /sorry abuse page
     * right after a search returns 200, so it is a poor landing page. DuckDuckGo
     * answers through the kernel and renders without a challenge.
     */
    const val DEFAULT_HOME = "https://duckduckgo.com/"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun home(ctx: Context): String =
        prefs(ctx).getString(KEY_HOME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOME

    fun setHome(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_HOME, normalize(url)).apply()
    }

    private const val KEY_TEXT_ZOOM = "textZoom"
    private const val KEY_DARK = "darkMode"

    /** Page text size in percent; 100 is the page's own default. */
    fun textZoom(ctx: Context): Int = prefs(ctx).getInt(KEY_TEXT_ZOOM, 100)

    fun setTextZoom(ctx: Context, zoom: Int) {
        prefs(ctx).edit().putInt(KEY_TEXT_ZOOM, zoom.coerceIn(50, 200)).apply()
    }

    fun darkMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DARK, false)

    fun setDarkMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DARK, on).apply()
    }

    /** Accepts "google.com" and stores an absolute URL. */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return DEFAULT_HOME
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }
}


