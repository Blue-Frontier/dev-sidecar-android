package org.blueFrontier.devSideCar.next.android

import android.content.Context

/**
 * Optional acceleration for GitHub downloads.
 *
 * Xget (github.com/xixu-me/Xget) mirrors github.com under a path prefix and
 * serves the same bytes, which is faster than the direct route on networks that
 * throttle or reset GitHub. It is a third party, so this is off unless the user
 * turns it on: with it enabled, the request — and only the request, since the
 * file is public — leaves for someone else's server.
 */
object Xget {

    const val KEY = "xget"

    private const val MIRROR = "https://xget.xi-xu.me/gh/"
    private const val GITHUB = "https://github.com/"

    fun enabled(ctx: Context): Boolean = BrowserPrefs.prefs(ctx).getBoolean(KEY, false)

    /** Returns the mirror URL for a github.com link, or the link unchanged. */
    fun rewrite(ctx: Context, url: String): String {
        if (!enabled(ctx)) return url
        // Only plain release/raw downloads: api.github.com and the asset CDN keep
        // their own hosts, and a signed URL would not survive the rewrite.
        if (!url.startsWith(GITHUB)) return url
        return MIRROR + url.removePrefix(GITHUB)
    }
}
