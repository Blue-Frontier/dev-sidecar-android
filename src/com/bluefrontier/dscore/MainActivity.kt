package com.bluefrontier.dscore

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var statusView: TextView
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var urlField: EditText
    private lateinit var toggle: Button
    private var vpnOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProxyVpnService.statusListener = { msg -> runOnUiThread { statusView.text = msg } }

        hostField = EditText(this).apply {
            hint = "代理地址"
            setText("127.0.0.1")
            isSingleLine = true
        }
        portField = EditText(this).apply {
            hint = "端口"
            setText("31181")
            isSingleLine = true
        }
        toggle = Button(this).apply {
            text = "开启 VPN"
            setOnClickListener { toggleVpn() }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hostField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(portField, LinearLayout.LayoutParams(220, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 16 })
            addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 16 })
        }

        urlField = EditText(this).apply {
            hint = "https://…"
            isSingleLine = true
        }
        val go = Button(this).apply {
            text = "打开"
            setOnClickListener { loadUrl() }
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 16 })
        }

        statusView = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            text = "未启动"
        }

        web = WebView(this).apply {
            webViewClient = KernelCaWebViewClient()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 0)
            addView(row1)
            addView(row2, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16 })
            addView(statusView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8 })
            addView(web, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = 16 })
        }
        setContentView(root)

        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        urlField.setText("https://github.com")
    }

    private fun loadUrl() {
        val url = urlField.text.toString().trim()
        if (url.isEmpty()) return
        web.loadUrl(if (url.contains("://")) url else "https://$url")
    }

    private fun toggleVpn() {
        if (vpnOn) {
            vpnOn = false
            toggle.text = "开启 VPN"
            val stop = Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP)
            startService(stop)
            statusView.text = "已请求关闭"
            return
        }
        val host = hostField.text.toString().trim().ifEmpty { "127.0.0.1" }
        val port = portField.text.toString().toIntOrNull() ?: 31181
        val pending = VpnService.prepare(this)
        if (pending != null) {
            statusView.text = "等待 VPN 授权…"
            startActivityForResult(pending, REQ_VPN)
            return
        }
        startVpn(host, port)
    }

    private fun startVpn(host: String, port: Int) {
        vpnOn = true
        toggle.text = "关闭 VPN"
        val i = Intent(this, ProxyVpnService::class.java)
            .setAction(ProxyVpnService.ACTION_START)
            .putExtra(ProxyVpnService.EXTRA_HOST, host)
            .putExtra(ProxyVpnService.EXTRA_PORT, port)
            .putExtra(ProxyVpnService.EXTRA_MODE, ProxyVpnService.MODE_PROXY)
        startService(i)
        statusView.text = "已启动：$host:$port（仅本 app 生效）"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) {
            val host = hostField.text.toString().trim().ifEmpty { "127.0.0.1" }
            val port = portField.text.toString().toIntOrNull() ?: 31181
            startVpn(host, port)
        } else {
            statusView.text = "已取消 VPN 授权"
        }
    }

    override fun onDestroy() {
        ProxyVpnService.statusListener = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_VPN = 1
    }
}
