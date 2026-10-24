package org.BlueFrontier.DevSidecar

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.InetSocketAddress

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var statusView: TextView
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var urlField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hostField = EditText(this).apply {
            hint = "内核地址"
            setText("127.0.0.1")
            isSingleLine = true
        }
        portField = EditText(this).apply {
            hint = "端口"
            setText("31181")
            isSingleLine = true
        }
        // Allow `am start --es host X --es port Y` (device automation, QR handoff, …)
        intent.getStringExtra("host")?.let { hostField.setText(it) }
        intent.getStringExtra("port")?.let { portField.setText(it) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hostField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(portField, LinearLayout.LayoutParams(220, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 16 })
        }

        urlField = EditText(this).apply {
            hint = "https://…"
            isSingleLine = true
        }
        urlField.setOnEditorActionListener { _, _, _ -> loadUrl(); true }

        statusView = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            text = "app 内拦截转发，无 VPN"
        }

        web = WebView(this).apply { webViewClient = ProxyFetchWebViewClient { kernelAddr() } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(urlField, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16 })
            addView(statusView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8 })
            addView(web, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = 16 })
        }
        // edge-to-edge (targetSdk 35+): reserve space for the status/nav bars ourselves.
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(32, insets.getSystemWindowInsetTop() + 32, 32, insets.getSystemWindowInsetBottom())
            insets
        }
        setContentView(root)

        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        urlField.setText("https://github.com")
    }

    private fun kernelAddr(): InetSocketAddress? {
        val host = hostField.text.toString().trim().ifEmpty { "127.0.0.1" }
        val port = portField.text.toString().toIntOrNull() ?: 31181
        if (port !in 1..65535) return null
        return InetSocketAddress(host, port)
    }

    private fun loadUrl() {
        val url = urlField.text.toString().trim()
        if (url.isEmpty()) return
        web.loadUrl(if (url.contains("://")) url else "https://$url")
    }
}
