package org.blueFrontier.devSideCar.next.android

import android.app.Activity
import android.os.Build

/**
 * True when this activity instance was started by `adb shell am start`.
 *
 * The `--es` extras are automation hooks (open a URL, switch modes, drive the
 * download list). Android 14 is the first release that reports who launched an
 * activity, so older devices ignore the hooks instead of exposing them to every
 * installed app. Starting a fresh instance with `am start -S` keeps the hooks
 * working because the launch source is then the shell.
 */
internal fun Activity.launchedFromShell(): Boolean =
    Build.VERSION.SDK_INT >= 34 &&
        launchedFromPackage in setOf("com.android.shell", "root")
