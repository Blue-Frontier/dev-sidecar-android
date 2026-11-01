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
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.net.InetSocketAddress

/**
 * The browser shell: one row (history, reload, home, address, tabs, settings)
 * over the active tab's WebView. Every tab owns its own WebView so each keeps
 * its own history and scroll position; only the active one is attached.
 */
class MainActivity : Activity() {

    /**
     * One tab. Only the visible tab has a live WebView: the others keep their
     * URL, title and visit log, so any number of tabs costs one renderer and a
     * switch reloads from the HTTP cache instead of holding them all in memory.
     */
    private class Tab(var url: String) {
        var title: String = ""

        /**
         * This tab's own visit log. The WebView keeps one global back/forward
         * list, so with a single WebView that list would mix every tab's pages;
         * recording here keeps back/forward per tab, at the cost of reloading
         * from cache when a tab is shown again.
         */
        val history = mutableListOf<String>()
        var cursor = -1
    }

    private val tabs = mutableListOf<Tab>()
    private var active = -1
    private lateinit var pageHost: FrameLayout
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

        // One row: history, reload, home, the address, tabs, settings. All six
        // buttons share one width so the row stays even.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(navButton("ic_history", "历史记录") { showHistory() })
            addView(navButton("ic_refresh", "刷新") { if (tabs.isNotEmpty()) web.reload() })
            addView(navButton("ic_home", "主页") { goHome() })
            addView(
                urlField,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                },
            )
            addView(navButton("ic_tabs", "标签页") { showTabs() })
            addView(navButton("ic_settings", "设置") {
                startActivity(android.content.Intent(this@MainActivity, KernelSettingsActivity::class.java))
            })
        }

        progress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal,
        ).apply {
            isIndeterminate = false
            visibility = android.view.View.GONE
        }
        pageHost = FrameLayout(this)
        web = WebView(this).apply {
            applySettings(this)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        pageHost.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CHROME_BG)
            addView(row)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6))
            addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Edge to edge: the page fills the screen width. Only the toolbar keeps
        // a horizontal inset, otherwise the dark window colour frames the white
        // page with a visible border on both sides.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)

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
        applyProxy { newTab(initial) }
    }

    // ---------------------------------------------------------------- tabs

    private fun newTab(url: String?) {
        val target = url ?: BrowserPrefs.home(this)
        tabs.add(Tab(target))
        switchTab(tabs.size - 1, force = true)
    }

    private fun switchTab(index: Int, force: Boolean = false) {
        if (index !in tabs.indices) return
        if (index == active && !force) return
        active = index
        val tab = tabs[index]
        urlField.setText(tab.url)
        progress.visibility = android.view.View.GONE
        web.loadUrl(tab.url)
        web.requestFocus()
    }

    /** Appends a visit to the tab's own history, dropping any forward entries. */
    private fun record(tab: Tab, url: String) {
        if (url.isBlank() || url == "about:blank") return
        if (tab.history.getOrNull(tab.cursor) == url) return
        while (tab.history.size > tab.cursor + 1) {
            tab.history.removeAt(tab.history.size - 1)
        }
        tab.history.add(url)
        tab.cursor = tab.history.size - 1
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tabs.removeAt(index)
        when {
            tabs.isEmpty() -> {
                active = -1
                web.loadUrl("about:blank")
                newTab(BrowserPrefs.home(this))
            }
            index == active -> {
                active = -1
                switchTab(index.coerceAtMost(tabs.size - 1), force = true)
            }
            index < active -> active -= 1
        }

    }

    private fun showTabs() {
        val labels = tabs.mapIndexed { index, tab ->
            val title = tab.title.ifBlank { tab.url.substringAfter("://").ifBlank { "新标签页" } }
            (if (index == active) "● " else "○ ") + title + "\n" + tab.url
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("标签页（${tabs.size}）")
            .setItems(labels) { _, which -> switchTab(which) }
            .setPositiveButton("新建") { _, _ -> newTab(BrowserPrefs.home(this)) }
            .setNeutralButton("关闭当前") { _, _ -> if (active in tabs.indices) closeTab(active) }
            .show()
    }

    /** This tab's own history, so back and forward share one button. */
    private fun showHistory() {
        val tab = tabs.getOrNull(active) ?: return
        if (tab.history.isEmpty()) return
        val labels = tab.history.mapIndexed { i, url ->
            (if (i == tab.cursor) "● " else "○ ") + url
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("历史记录")
            .setItems(labels) { _, which ->
                tab.cursor = which
                tab.url = tab.history[which]
                web.loadUrl(tab.url)
            }
            .setNeutralButton("后退") { _, _ ->
                if (tab.cursor > 0) {
                    tab.cursor -= 1
                    tab.url = tab.history[tab.cursor]
                    web.loadUrl(tab.url)
                }
            }
            .setPositiveButton("前进") { _, _ ->
                if (tab.cursor + 1 < tab.history.size) {
                    tab.cursor += 1
                    tab.url = tab.history[tab.cursor]
                    web.loadUrl(tab.url)
                }
            }
            .show()
    }

    // -------------------------------------------------------------- webview

    private fun applySettings(view: WebView) {
        val settings = view.settings
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

        view.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(wv: WebView, newProgress: Int) {

                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) {
                    android.view.View.GONE
                } else {
                    android.view.View.VISIBLE
                }
            }

            override fun onReceivedTitle(wv: WebView, title: String) {
                tabs.getOrNull(active)?.title = title
            }
        }
        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun doUpdateVisitedHistory(wv: WebView, url: String, isReload: Boolean) {
                tabs.getOrNull(active)?.let {
                    it.url = url
                    record(it, url)
                }
                if (!urlField.hasFocus()) urlField.setText(url)
            }

            override fun onPageStarted(wv: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progress.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(wv: WebView, url: String) {
                progress.visibility = android.view.View.GONE
            }

            override fun onReceivedSslError(
                wv: WebView,
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

    // -------------------------------------------------------------- plumbing

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
        pageHost.removeView(web)
        web.destroy()
        tabs.clear()
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
            if (tabs.isNotEmpty()) web.loadUrl(it)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentExtras(intent)
    }

    override fun onPause() {
        super.onPause()
        if (tabs.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_LAST_URL, web.url ?: urlField.text.toString())
                .apply()
        }
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

    /**
     * Evenly sized icon buttons for the navigation row. The drawable is looked
     * up by name because this build does not generate an R class.
     */
    private fun navButton(icon: String, description: String, onClick: () -> Unit): android.widget.ImageButton =
        android.widget.ImageButton(this).apply {
            val id = resources.getIdentifier(icon, "drawable", packageName)
            if (id != 0) setImageResource(id)
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            imageTintList = android.content.res.ColorStateList.valueOf(CHROME_TEXT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            backgroundTintList = android.content.res.ColorStateList.valueOf(CHROME_SURFACE)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(38))
            setOnClickListener { onClick() }
        }

    private fun goHome() {
        val home = BrowserPrefs.home(this)
        urlField.setText(home)
        if (tabs.isNotEmpty()) web.loadUrl(home) else newTab(home)
    }

    private fun loadUrl() {
        val raw = urlField.text.toString().trim()
        if (raw.isEmpty()) return
        // Enter used to leave the keyboard covering the page, which read as
        // "nothing happened". Dismiss it and hand focus back to the WebView.
        hideKeyboard()
        val target = if (raw.contains("://") || raw.startsWith("about:")) raw else "https://$raw"
        if (tabs.isEmpty()) newTab(target) else web.loadUrl(target)
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(urlField.windowToken, 0)
        urlField.clearFocus()
        if (tabs.isNotEmpty()) web.requestFocus()
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







