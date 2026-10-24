package com.bluefrontier.dscore

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Fetches every WebView resource through the kernel HTTP proxy with an
 * explicit `Proxy(...)` — no VpnService involved.
 *
 * Why: WebView has no proxy API, and (observed on device) Chromium also
 * ignores the per-network proxy of a routeless VPN even when the process is
 * bound to that network. Interception is the only app-level hook that lets us
 * hand-pick the transport, so the WebView never opens its own connections.
 *
 * The kernel MITM terminates TLS, so this client trusts exactly two issuers:
 * the system trust store (plain http / direct upstreams) and the kernel CA
 * ("dev-sidecar-core CA", issuer-name check as before). Connections only ever
 * target the loopback proxy, so the loose issuer check is acceptable.
 *
 * Known limits of shouldInterceptRequest (documented Android behavior):
 * the request body is not exposed, so non-GET/HEAD requests answer 501.
 */
class ProxyFetchWebViewClient(private val proxyAddr: () -> InetSocketAddress?) : WebViewClient() {

    companion object {
        private const val TAG = "ds-fetch"
        const val KERNEL_CA_ISSUER = "dev-sidecar-core CA"

        /** Hop-by-hop / unsettable headers the kernel proxy manages itself. */
        private val STRIP_REQUEST = setOf(
            "host", "connection", "content-length",
            "upgrade", "proxy-connection", "te", "trailer", "transfer-encoding",
        )

        /** Response headers the WebView must not receive verbatim: the stream
         * we hand over is already framed by WebResourceResponse. */
        private val STRIP_RESPONSE =
            setOf("connection", "transfer-encoding", "keep-alive", "proxy-connection")

        private val tls = KernelTrust.setup()
    }

    override fun shouldInterceptRequest(
        view: android.webkit.WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val addr = proxyAddr() ?: return errorPage(
            503, "内核未运行",
            "<p>未配置内核地址，无法转发。</p>",
        )
        return try {
            fetch(request, addr)
        } catch (e: Exception) {
            Log.w(TAG, "fetch via kernel failed: ${e.message}")
            errorPage(
                502, "内核转发失败",
                "<p>${e.javaClass.simpleName}: ${e.message ?: "unknown"}</p>" +
                    "<p>${request.url}</p>",
            )
        }
    }

    // ---- fetch ------------------------------------------------------------

    private fun fetch(request: WebResourceRequest, addr: InetSocketAddress): WebResourceResponse? {
        val method = request.method.uppercase()
        if (method != "GET" && method != "HEAD") {
            // shouldInterceptRequest does not expose the request body.
            return errorPage(
                501, "该请求方式暂不支持",
                "<p>app 内转发模式无法携带 $method 请求体（Android 平台限制）。</p>" +
                    "<p>${request.url}</p>",
            )
        }

        val proxy = Proxy(Proxy.Type.HTTP, addr)
        val reqUrl = urlWithoutFragment(request.url)
        val conn = URL(reqUrl)
            .openConnection(proxy) as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true

        // Replay cookies the WebView already accumulated (set by previous
        // responses or page script) so subsequent requests keep the session.
        android.webkit.CookieManager.getInstance().getCookie(reqUrl)?.let {
            conn.setRequestProperty("Cookie", it)
        }

        var acceptEncoding = "identity" // default below, replaced by the WebView's own list
        for ((k, v) in request.requestHeaders) {
            val key = k.lowercase()
            if (key in STRIP_REQUEST) continue
            if (key == "if-none-match" || key == "if-modified-since") continue // no 304 churn
            if (key == "accept-encoding") {
                acceptEncoding = v
                continue
            }
            conn.setRequestProperty(k, v)
        }
        // Forwarded verbatim: the WebView does its own decoding of encodings it
        // asked for, so the raw stream we hand back matches its expectation.
        conn.setRequestProperty("Accept-Encoding", acceptEncoding)

        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = tls.first
            conn.hostnameVerifier = tls.second
        }

        val code = conn.responseCode
        val stream = if (code in 400..599) conn.errorStream ?: conn.inputStream else conn.inputStream
            ?: ByteArrayInputStream(ByteArray(0))

        val raw = conn.headerFields ?: emptyMap()

        // Forward Set-Cookie into the WebView cookie store so page script and
        // later navigations see the same session state the kernel negotiated.
        val cookieKey = raw.keys.filterNotNull().firstOrNull { it.equals("Set-Cookie", true) }
        if (cookieKey != null) {
            val wm = android.webkit.CookieManager.getInstance()
            for (c in raw[cookieKey] ?: emptyList()) {
                runCatching { wm.setCookie(request.url.host, c) }
            }
        }

        val headers = raw
            .filterKeys { it != null }
            .mapKeys { it.key as String }
            .filterKeys { it.lowercase() !in STRIP_RESPONSE }
            .mapValues { (_, v) -> v.joinToString(", ") } // WebResourceResponse takes flat strings

        val contentType = conn.contentType ?: "application/octet-stream"
        // Split mime from charset ("text/html; charset=utf-8").
        val parts = contentType.split(";")
        val mime = parts[0].trim().ifEmpty { "application/octet-stream" }
        val charset = parts.getOrNull(1)
            ?.trim()
            ?.removePrefix("charset=")
            ?.trim('"', ' ')
            ?.ifEmpty { null }

        return WebResourceResponse(mime, charset, code, reasonOf(code), headers, stream)
    }

    private fun reasonOf(code: Int): String = when (code) {
        200 -> "OK"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status $code"
    }

    private fun urlWithoutFragment(uri: Uri): String {
        val s = uri.toString()
        val hash = s.indexOf('#')
        return if (hash >= 0) s.substring(0, hash) else s
    }

    private fun errorPage(code: Int, title: String, bodyHtml: String): WebResourceResponse {
        val html = "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "<body style=\"font-family:sans-serif;background:#fafafa;padding:24px\">" +
            "<h3>$title ($code)</h3>$bodyHtml</body>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        return WebResourceResponse(
            "text/html", "utf-8", code, reasonOf(code),
            mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store"),
            ByteArrayInputStream(bytes),
        )
    }
}

/**
 * TLS trust for the loopback kernel: try the system trust store first, fall
 * back to "issued by the kernel CA" (issuer-name match, as the previous
 * WebViewClient proceeded on the same check).
 */
object KernelTrust {

    fun setup(): Pair<SSLSocketFactory, HostnameVerifier> {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        system.init(null as java.security.KeyStore?)
        val systemTm = system.trustManagers.filterIsInstance<X509TrustManager>().first()

        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                systemTm.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    val kernelIssued = chain.any { cert ->
                        cert.issuerX500Principal.name.contains(ProxyFetchWebViewClient.KERNEL_CA_ISSUER) ||
                            cert.issuerDN.name.contains(ProxyFetchWebViewClient.KERNEL_CA_ISSUER)
                    }
                    if (!kernelIssued) throw e
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers
        }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        val verifier = HostnameVerifier { host, session ->
            // Default algorithm first (kernel leaf certs carry the right SANs);
            // accept kernel-issued chains otherwise.
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session) ||
                session.peerCertificates.any { c ->
                    (c as? X509Certificate)?.issuerDN?.name
                        ?.contains(ProxyFetchWebViewClient.KERNEL_CA_ISSUER) == true
                }
        }
        return ctx.socketFactory to verifier
    }
}
