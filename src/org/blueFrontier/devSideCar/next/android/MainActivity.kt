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
    private val proxyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var progress: android.widget.ProgressBar
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
            // Tapping the address should replace it, not append at the caret.
            setSelectAllOnFocus(true)
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
            setPadding(dp(10), dp(6), dp(10), dp(6))
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

        progress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal,
        ).apply {
            isIndeterminate = false
            visibility = android.view.View.GONE
        }

        web = WebView(this).apply {
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    val bar = this@MainActivity.progress
                    bar.progress = newProgress
                    bar.visibility = if (newProgress >= 100) {
                        android.view.View.GONE
                    } else {
                        android.view.View.VISIBLE
                    }
                }
            }
            // The whole WebView goes through the kernel as an HTTP proxy
            // (applyProxy). Per-request interception cannot carry a POST body,
            // which is what broke every login form.
            webViewClient = object : android.webkit.WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (!urlField.hasFocus()) urlField.setText(url)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    this@MainActivity.progress.visibility = android.view.View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    this@MainActivity.progress.visibility = android.view.View.GONE
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: android.webkit.SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    // The kernel CA is an app trust anchor, so its chains
                    // validate normally; this covers the rest.
                    val issuer = error.certificate?.issuedBy?.cName ?: ""
                    if (issuer.contains(KERNEL_CA_ISSUER)) handler.proceed() else handler.cancel()
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CHROME_BG)
            addView(row)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6))
            addView(
                web,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 12 },
            )
        }
        // edge-to-edge (targetSdk 35+): reserve space for the status/nav bars ourselves.
        // Edge to edge: the page fills the screen width. Only the toolbar keeps
        // a horizontal inset, otherwise the dark window colour frames the white
        // page with a visible border on both sides.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
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
        // The override is asynchronous; navigate only once it is in place.
        applyProxy { web.loadUrl(initial) }
    }

    /** Points the WebView at the in-process kernel. */
    private fun applyProxy(then: () -> Unit) {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) {
            // Old WebView provider: fall back to loading without the kernel.
            android.util.Log.w("ds-web", "PROXY_OVERRIDE unsupported by this WebView")
            then()
            return
        }
        val port = KernelRuntime.savedPort(this)
        val config = androidx.webkit.ProxyConfig.Builder()
            .addProxyRule("127.0.0.1:$port")
            .build()
        androidx.webkit.ProxyController.getInstance().setProxyOverride(
            config,
            proxyExecutor,
        ) {
            android.util.Log.i("ds-web", "webview proxy -> 127.0.0.1:$port")
            runOnUiThread { then() }
        }
    }

    override fun onDestroy() {
        runCatching {
            androidx.webkit.ProxyController.getInstance().clearProxyOverride(proxyExecutor) {}
        }
        super.onDestroy()
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
        val raw = urlField.text.toString().trim()
        if (raw.isEmpty()) return
        // Enter used to leave the keyboard covering the page, which read as
        // "nothing happened". Dismiss it and hand focus back to the WebView.
        hideKeyboard()
        val target = if (raw.contains("://") || raw.startsWith("about:")) raw else "https://$raw"
        web.loadUrl(target)
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(urlField.windowToken, 0)
        urlField.clearFocus()
        web.requestFocus()
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












