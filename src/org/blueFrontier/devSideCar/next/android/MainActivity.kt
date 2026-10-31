package org.blueFrontier.devSideCar.next.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import java.net.InetSocketAddress

/**
 * The browser shell: one navigation row (back, forward, reload, home, address,
 * settings) over a WebView whose requests are fetched through the kernel.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlField: EditText

    private val prefs by lazy { getSharedPreferences("ds-app", MODE_PRIVATE) }
    private val kernelPrefs by lazy { getSharedPreferences("ds-kernel", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Allow chrome://inspect to attach while the render path is being
        // stabilised; harmless for a debug-signed build.
        WebView.setWebContentsDebuggingEnabled(true)

        urlField = EditText(this).apply {
            hint = "输入网址或搜索"
            isSingleLine = true
            textSize = 14f
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setTextColor(CHROME_TEXT)
            setHintTextColor(CHROME_MUTED)
            setBackgroundColor(CHROME_SURFACE)
            setPadding(24, 12, 24, 12)
        }
        urlField.setOnEditorActionListener { _, _, _ -> loadUrl(); true }
        // A hardware Enter does not produce an IME action, so handle the key too.
        urlField.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                loadUrl()
                return@setOnKeyListener true
            }
            false
        }

        // One row: navigation, the address in the middle, settings on the right.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(navButton("←") { if (web.canGoBack()) web.goBack() })
            addView(navButton("→") { if (web.canGoForward()) web.goForward() })
            addView(navButton("⟳") { web.reload() })
            addView(navButton("⌂") { goHome() })
            addView(
                urlField,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 16
                    marginEnd = 16
                },
            )
            addView(Button(this@MainActivity).apply {
                text = "⚙"
                setTextColor(CHROME_TEXT)
                backgroundTintList = android.content.res.ColorStateList.valueOf(CHROME_SURFACE)
                setOnClickListener {
                    startActivity(android.content.Intent(this@MainActivity, KernelSettingsActivity::class.java))
                }
            })
        }

        web = WebView(this).apply {
            webViewClient = ProxyFetchWebViewClient(
                proxyAddr = { kernelAddr() },
                onUrl = { url -> if (!urlField.hasFocus()) urlField.setText(url) },
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CHROME_BG)
            addView(row)
            addView(
                web,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 12 },
            )
        }
        // edge-to-edge (targetSdk 35+): reserve space for the status/nav bars ourselves.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(32, insets.systemWindowInsetTop + 32, 32, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)

        val settings = web.settings
        // `am start --ez nojs true` keeps script off for one run, which is how
        // the "is Google's /sorry redirect script-driven?" question gets a
        // straight answer on a real device.
        settings.javaScriptEnabled = !intent.getBooleanExtra("nojs", false)
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Google's page script redirects anything advertising itself as an
        // embedded WebView (the "; wv" marker and "Version/4.0") to its
        // /sorry/index abuse page right after a search returns 200. The renderer
        // is ordinary Chromium, so report the plain mobile Chrome identity.
        settings.userAgentString = settings.userAgentString
            .replace("; wv)", ")")
            .replace(Regex("""\s*Version/\S+"""), "")

        // Logins must survive a restart: the WebView keeps localStorage and
        // IndexedDB on disk under the app's private dir, cookies are persisted
        // when flushed in onPause, and third-party cookies keep OAuth and CDN
        // asset flows working. The last URL is restored too.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        // The kernel lives in this process: bring it up synchronously so the
        // listener is bound before the first navigation, then let the service
        // hold the foreground notice and wake lock while we browse.
        if (kernelPrefs.getBoolean(KEY_AUTO_START, true)) {
            val listen = KernelRuntime.savedListen(this)
            KernelRuntime.start(this, listen)
            KernelService.start(this, listen)
        }

        // Hidden entry for the overwall (增强) mode: the mode row omits it, but
        // `am start ... --es mode ow` still applies it. MainActivity is the only
        // exported activity, so the switch lives here.
        applyIntentExtras(intent)

        // `am start --es url X` drives a load from adb (automation, QR handoff);
        // otherwise resume the last page, and on a fresh install open the home page.
        val startUrl = intent.getStringExtra("url") ?: prefs.getString(KEY_LAST_URL, null)
        val initial = startUrl ?: BrowserPrefs.home(this)
        urlField.setText(initial)
        web.loadUrl(initial)
    }

    /**
     * Applies the adb extras. Only onCreate and onNewIntent call this: replaying
     * it from onResume would re-assert a stale `mode` every time the user comes
     * back from settings, overriding whatever they just picked there.
     *
     * `am start` on a running instance may be swallowed as a task bring-to-front
     * (no onCreate, no onNewIntent), so switching modes from adb needs `-S`.
     */
    private fun applyIntentExtras(from: android.content.Intent) {
        from.getStringExtra("mode")?.takeIf { it.isNotBlank() }?.let { requested ->
            val ok = KernelRuntime.setMode(this, requested)
            android.util.Log.i(
                "ds-mode",
                "extra=$requested ok=$ok current=${KernelRuntime.currentMode(this)}",
            )
        }
        from.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let {
            urlField.setText(it)
            web.loadUrl(it)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        prefs.edit()
            .putString(KEY_LAST_URL, web.url ?: urlField.text.toString())
            .apply()
        // Cookie writes are asynchronous; without this a freshly issued login
        // cookie can be lost if the process is killed soon after.
        CookieManager.getInstance().flush()
    }

    private fun kernelAddr(): InetSocketAddress? {
        val listen = if (KernelRuntime.isRunning()) KernelRuntime.listenAddr() else KernelRuntime.savedListen(this)
        val host = listen.substringBeforeLast(':').ifEmpty { DEFAULT_HOST }
        val port = listen.substringAfterLast(':').toIntOrNull() ?: DEFAULT_PORT
        if (port !in 1..65535) return null
        return InetSocketAddress(host, port)
    }

    /** Round, roomy buttons for the navigation row. */
    private fun navButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            minWidth = 0
            minimumWidth = 0
            setPadding(20, 4, 20, 4)
            setTextColor(CHROME_TEXT)
            backgroundTintList = android.content.res.ColorStateList.valueOf(CHROME_SURFACE)
            setOnClickListener { onClick() }
        }

    private fun goHome() {
        val home = BrowserPrefs.home(this)
        urlField.setText(home)
        web.loadUrl(home)
    }

    private fun loadUrl() {
        val url = urlField.text.toString().trim()
        if (url.isEmpty()) return
        web.loadUrl(if (url.contains("://")) url else "https://$url")
    }

    companion object {
        private val CHROME_BG = Color.parseColor("#0F1115")
        private val CHROME_SURFACE = Color.parseColor("#1B1F26")
        private val CHROME_TEXT = Color.parseColor("#E6E6E6")
        private val CHROME_MUTED = Color.parseColor("#8A8A8A")

        private const val KEY_LAST_URL = "lastUrl"
        private const val KEY_AUTO_START = "autoStart"

        const val DEFAULT_PORT = KernelRuntime.DEFAULT_PORT
        const val DEFAULT_HOST = "127.0.0.1"
    }
}





