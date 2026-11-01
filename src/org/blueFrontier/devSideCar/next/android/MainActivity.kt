package org.blueFrontier.devSideCar.next.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.provider.MediaStore
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
        /** False for a restored tab that has not been shown yet. */
        var loaded = false
    }

    private val tabs = mutableListOf<Tab>()
    private var active = -1
    private lateinit var pageHost: FrameLayout
    private lateinit var web: WebView

    private lateinit var urlField: EditText

    private val prefs by lazy { getSharedPreferences("ds-app", MODE_PRIVATE) }
    private val proxyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var progress: android.widget.ProgressBar
    /** Pending <input type="file"> chooser; its result goes back to the page. */
    private var pendingFiles: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    /** Camera/microphone request waiting for the runtime permission answer. */
    private var pendingPermission: android.webkit.PermissionRequest? = null
    private var desktopMode = false
    /** Fullscreen video container, stacked on top of the content view. */
    private var fullscreen: android.widget.FrameLayout? = null
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
            addView(navButton("ic_settings", "更多") { showMenu() })
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
            // Without a listener the WebView silently ignores anything that is
            // not navigable, which makes every download link look broken.
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                download(url, userAgent, contentDisposition, mimeType)
            }
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
        // A link handed to us (other apps, adb) outranks the restored session:
        // without this the tab restore swallows every incoming URL.
        val incoming = intentUrl(intent)
        val startUrl = incoming ?: prefs.getString(KEY_LAST_URL, null)
        val initial = startUrl ?: BrowserPrefs.home(this)
        urlField.setText(initial)
        // The override is asynchronous; navigate only once it is in place.
        applyProxy {
            if (incoming != null) newTab(incoming) else if (!restoreTabs()) newTab(initial)
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun newTab(url: String?, blank: Boolean = false, load: Boolean = true) {
        val target = url ?: if (blank) "about:blank" else BrowserPrefs.home(this)
        tabs.add(Tab(target))
        if (!load) {
            active = tabs.size - 1
            tabs[active].loaded = false
            urlField.setText(target)
            return
        }
        switchTab(tabs.size - 1, force = true)
    }

    private fun switchTab(index: Int, force: Boolean = false) {
        if (index !in tabs.indices) return
        if (index == active && !force) return
        active = index
        val tab = tabs[index]
        urlField.setText(tab.url)
        progress.visibility = android.view.View.GONE
        if (!tab.loaded) {
            tab.loaded = true
            web.loadUrl(tab.url)
        }
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

        settings.textZoom = BrowserPrefs.textZoom(this)
        applyDarkMode(settings)

        view.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(wv: WebView, newProgress: Int) {

                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) {
                    android.view.View.GONE
                } else {
                    android.view.View.VISIBLE
                }
            }

            override fun onJsAlert(
                wv: WebView, url: String, message: String, result: android.webkit.JsResult,
            ): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                wv: WebView, url: String, message: String, result: android.webkit.JsResult,
            ): Boolean {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                wv: WebView, url: String, message: String, defaultValue: String?,
                result: android.webkit.JsPromptResult,
            ): Boolean {
                val input = android.widget.EditText(this@MainActivity).apply {
                    setText(defaultValue ?: "")
                    setSelection(text.length)
                }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val missing = request.resources.mapNotNull { resource ->
                    when (resource) {
                        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> android.Manifest.permission.CAMERA
                        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> android.Manifest.permission.RECORD_AUDIO
                        else -> null
                    }
                }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    request.grant(request.resources)
                    return
                }
                pendingPermission = request
                requestPermissions(missing.toTypedArray(), REQ_MEDIA)
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message,
            ): Boolean {
                // target=_blank and window.open land here: give the popup its own
                // tab and hand that WebView back through the transport.
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                newTab(null, blank = true)
                transport.webView = web
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(
                view: android.view.View, callback: android.webkit.WebChromeClient.CustomViewCallback,
            ) {
                if (fullscreen != null) {
                    callback.onCustomViewHidden()
                    return
                }
                val frame = android.widget.FrameLayout(this@MainActivity).apply {
                    setBackgroundColor(Color.BLACK)
                    tag = callback
                }
                frame.addView(
                    view,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                fullscreen = frame
                addContentView(
                    frame,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }

            override fun onHideCustomView() {
                val frame = fullscreen ?: return
                (frame.parent as? ViewGroup)?.removeView(frame)
                fullscreen = null
                (frame.tag as? android.webkit.WebChromeClient.CustomViewCallback)?.onCustomViewHidden()
            }

            override fun onShowFileChooser(
                wv: WebView,
                callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                params: android.webkit.WebChromeClient.FileChooserParams,
            ): Boolean {
                // Without this the <input type="file"> tap does nothing at all.
                pendingFiles?.onReceiveValue(null)
                pendingFiles = callback
                return runCatching {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER)
                    true
                }.getOrElse {
                    pendingFiles = null
                    callback.onReceiveValue(null)
                    false
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
                    it.loaded = true
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

            override fun onReceivedError(
                wv: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                val html = """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>body{font:16px -apple-system,sans-serif;padding:32px;color:#333}
                    code{background:#f2f2f2;padding:2px 6px;border-radius:4px}</style></head>
                    <body><h3>打不开这个页面</h3>
                    <p>${request.url}</p>
                    <p>${error.description}（${error.errorCode}）</p>
                    <p>可以检查网络，或到设置里确认内核正在运行。</p></body></html>
                """.trimIndent()
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
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

    // ------------------------------------------------------------- downloads

    private fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        Downloads.start(this, url, userAgent ?: web.settings.userAgentString, contentDisposition, mimeType)
        toast("已加入下载列表")
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
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
    /** The URL a fresh intent asks for: an explicit extra or a VIEW link. */
    private fun intentUrl(intent: android.content.Intent): String? {
        intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let { return it }
        val data = intent.data ?: return null
        return data.toString().takeIf { data.scheme == "http" || data.scheme == "https" }
    }

    private fun applyIntentExtras(from: android.content.Intent) {
        from.getStringExtra("mode")?.takeIf { it.isNotBlank() }?.let { requested ->
            val ok = KernelRuntime.setMode(this, requested)
            android.util.Log.i(
                "ds-mode",
                "extra=$requested ok=$ok current=${KernelRuntime.currentMode(this)}",
            )
        }
        intentUrl(from)?.let { url ->
            urlField.setText(url)
            if (tabs.isNotEmpty()) openUrl(url) else newTab(url)
        }
        // `am start --ez downloads true` opens the download list, which is the
        // only way to drive a transfer from adb (the activity is not exported).
        if (from.getBooleanExtra("downloads", false)) {
            startActivity(android.content.Intent(this, DownloadsActivity::class.java))
        }
        // Automation hooks: touch input does not reach the app on every device,
        // and these make the menu and the search path checkable from adb.
        if (from.getBooleanExtra("menu", false)) showMenu()
        from.getStringExtra("search")?.takeIf { it.isNotBlank() }?.let { openUrl(searchUrl(it)) }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (tabs.isNotEmpty() && web.canGoBack()) {
            web.goBack()
            return
        }
        if (tabs.size > 1) {
            closeTab(active)
            return
        }
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MEDIA) return
        val request = pendingPermission ?: return
        pendingPermission = null
        if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            request.grant(request.resources)
        } else {
            request.deny()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = pendingFiles ?: return
        pendingFiles = null
        val picked: Array<android.net.Uri>? = when {
            resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(picked)
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
            tabs.getOrNull(active)?.let {
                BrowserData.addHistory(this, it.url, it.title)
            }
        }
        saveTabs()
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
        val target = if (raw.contains("://") || raw.startsWith("about:")) {
            raw
        } else if (looksLikeHost(raw)) {
            "https://$raw"
        } else {
            searchUrl(raw)
        }
        if (tabs.isEmpty()) newTab(target) else web.loadUrl(target)
    }

    /** A bare word with no dot is a search, not a host. */
    private fun looksLikeHost(text: String): Boolean {
        if (text.contains(' ') || text.contains('　')) return false
        val host = text.substringBefore('/')
        return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }

    private fun searchUrl(query: String): String =
        "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(query, "UTF-8")

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        val items = arrayOf(
            "设置", "分享本页", "页内查找", "书签", "历史记录",
            "请求桌面版", "清除浏览数据", "下载管理",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(android.content.Intent(this, KernelSettingsActivity::class.java))
                    1 -> sharePage()
                    2 -> findInPage()
                    3 -> showBookmarks()
                    4 -> showHistoryAll()
                    5 -> toggleDesktopMode()
                    6 -> clearBrowsingData()
                    7 -> startActivity(android.content.Intent(this, DownloadsActivity::class.java))
                }
            }
            .show()
    }

    private fun sharePage() {
        val url = web.url ?: urlField.text.toString()
        if (url.isBlank()) return
        startActivity(
            android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, url),
        )
    }

    private fun findInPage() {
        val input = android.widget.EditText(this).apply { hint = "在页面中查找" }
        android.app.AlertDialog.Builder(this)
            .setTitle("页内查找")
            .setView(input)
            .setPositiveButton("查找") { _, _ ->
                val query = input.text.toString()
                if (query.isNotBlank()) web.findAllAsync(query)
            }
            .setNeutralButton("下一个") { _, _ -> web.findNext(true) }
            .setNegativeButton("关闭") { _, _ -> web.clearMatches() }
            .show()
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        web.settings.userAgentString = if (desktopMode) DESKTOP_UA else mobileUserAgent()
        web.settings.useWideViewPort = desktopMode
        web.loadUrl(web.url ?: urlField.text.toString())
        toast(if (desktopMode) "已请求桌面版" else "已切回移动版")
    }

    private fun showBookmarks() {
        val entries = BrowserData.bookmarks(this)
        if (entries.isEmpty()) {
            toast("还没有书签，长按「更多」菜单里的书签可以添加当前页面")
            return
        }
        val labels = entries.map { (it.title.ifBlank { it.url }) }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("书签")
            .setItems(labels) { _, which -> openUrl(entries[which].url) }
            .setPositiveButton("收藏本页") { _, _ ->
                val url = web.url ?: return@setPositiveButton
                val mine = tabs.getOrNull(active)?.title ?: url
                val added = BrowserData.toggleBookmark(this, url, mine)
                toast(if (added) "已加入书签" else "已取消收藏")
            }
            .setNeutralButton("取消") { _, _ -> }
            .show()
    }

    private fun showHistoryAll() {
        val entries = BrowserData.history(this)
        if (entries.isEmpty()) {
            toast("还没有历史记录")
            return
        }
        val labels = entries.map { (it.title.ifBlank { it.url }) }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("历史记录")
            .setItems(labels) { _, which -> openUrl(entries[which].url) }
            .setNegativeButton("关闭") { _, _ -> }
            .setPositiveButton("清空") { _, _ ->
                BrowserData.clearHistory(this)
                toast("历史已清空")
            }
            .show()
    }

    private fun clearBrowsingData() {
        android.app.AlertDialog.Builder(this)
            .setTitle("清除浏览数据")
            .setMessage("会清除 Cookie、缓存、表单数据和历史记录，书签保留。登录状态会丢失。")
            .setPositiveButton("清除") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                web.clearCache(true)
                web.clearHistory()
                web.clearFormData()
                BrowserData.clearHistory(this)
                toast("已清除")
            }
            .setNegativeButton("取消") { _, _ -> }
            .show()
    }

    private fun openUrl(url: String) {
        if (tabs.isEmpty()) newTab(url) else {
            tabs[active].url = url
            tabs[active].loaded = true
            web.loadUrl(url)
        }
    }

    /** Algorithmic darkening where the WebView supports it, legacy force-dark otherwise. */
    private fun applyDarkMode(settings: WebSettings) {
        val on = BrowserPrefs.darkMode(this)
        when {
            androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING,
            ) -> androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, on)
            androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.FORCE_DARK,
            ) -> androidx.webkit.WebSettingsCompat.setForceDark(
                settings,
                if (on) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON
                else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF,
            )
        }
    }

    private fun mobileUserAgent(): String =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // ------------------------------------------------------------ tab state

    /** Tabs survive a process death; only the visible one has been rendered. */
    private fun saveTabs() {
        if (tabs.isEmpty()) return
        val array = org.json.JSONArray()
        for (tab in tabs) {
            array.put(org.json.JSONObject().put("url", tab.url).put("title", tab.title))
        }
        prefs.edit()
            .putString(KEY_TABS, array.toString())
            .putInt(KEY_TAB_INDEX, active)
            .apply()
    }

    private fun restoreTabs(): Boolean {
        val raw = prefs.getString(KEY_TABS, null) ?: return false
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return false
        if (array.length() == 0) return false
        val urls = (0 until array.length()).mapNotNull {
            array.optJSONObject(it)?.optString("url")?.takeIf { url -> url.isNotBlank() }
        }
        if (urls.isEmpty()) return false
        urls.forEach { newTab(it, load = false) }
        val index = prefs.getInt(KEY_TAB_INDEX, 0).coerceIn(0, urls.size - 1)
        active = index
        val tab = tabs[index]
        urlField.setText(tab.url)
        tab.loaded = true
        web.loadUrl(tab.url)
        return true
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
        private const val REQ_FILE_CHOOSER = 4711
        private const val REQ_MEDIA = 4712
        private const val KEY_TABS = "tabs"
        private const val KEY_TAB_INDEX = "tabIndex"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val KEY_AUTO_START = "autoStart"

        const val DEFAULT_PORT = KernelRuntime.DEFAULT_PORT
        const val DEFAULT_HOST = "127.0.0.1"
    }
}



















