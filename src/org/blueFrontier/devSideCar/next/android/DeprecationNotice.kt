package org.blueFrontier.devSideCar.next.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The developer-verification notice, inlined from
 * [woheller69/FreeDroidWarn](https://github.com/woheller69/FreeDroidWarn)
 * (Apache-2.0: three strings and one AlertDialog).
 *
 * The library ships no R class, so a no-Gradle build has to generate one and merge
 * its resources; that is a lot of build machinery for one dialog, hence this copy.
 */
object DeprecationNotice {

    private const val PREFS = "deprecation-notice"
    private const val KEY_VERSION = "shown-for-version"
    private const val MORE_INFO = "https://keepandroidopen.org/"

    // 逐字取自原库的 values-zh-rCN/values-zh-rCN.xml
    private const val MESSAGE =
        "Google（谷歌）宣布，自 2026/2027 年起，所有在经过 Google 认证的 Android 设备上运行的应用，" +
            "其开发者都必须直接向 Google 提交个人身份信息。由于本应用的开发者不同意此项要求，" +
            "届时本应用将无法在经过 Google 认证的 Android 设备上继续运行。"

    /** Shows the notice once per version, the way the library it replaces does. */
    fun showOnce(context: Context, versionCode: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= versionCode) return
        AlertDialog.Builder(context)
            .setMessage(MESSAGE)
            .setPositiveButton("详情") { _, _ ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MORE_INFO)))
                }
            }
            .setNegativeButton("知道了", null)
            .setOnDismissListener { prefs.edit().putInt(KEY_VERSION, versionCode).apply() }
            .show()
    }
}
