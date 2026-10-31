package org.blueFrontier.devSideCar.next.android

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches every http(s) request through the kernel instead of letting the
 * WebView hit the network, so the kernel's rules apply to the rendered page.
 *
 * Known limits of shouldInterceptRequest (documented Android behavior):
 * the request body is not exposed, so non-GET/HEAD requests answer 501.
 */
class ProxyFetchWebViewClient(
    private val proxyAddr: () -> InetSocketAddress?,
    /** Keeps the address bar in step with in-page navigation. */
    private val onUrl: ((String) -> Unit)? = null,
) : WebViewClient() {

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        onUrl?.invoke(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        Log.w(
            TAG,
            "webview error ${error.errorCode} ${error.description} main=${request.isForMainFrame} ${request.url}",
        )
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (request.isForMainFrame) Log.d(TAG, "main frame -> ${request.method} ${request.url}")
        val addr = proxyAddr() ?: return errorPage(
            503, "内核未运行",
            "<p>未配置内核地址，无法转发。</p>",
        )
        // The kernel may spend several seconds racing blocked addresses before
        // it finds a usable one, so one retry is worth far more than a fast fail.
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                return fetch(request, addr)
            } catch (e: java.io.IOException) {
                last = e
                Log.w(TAG, "fetch attempt ${attempt + 1} timed out/failed: ${e.message}")
            } catch (e: Exception) {
                last = e
            }
        }
        val e = last ?: IllegalStateException("unknown failure")
        Log.w(TAG, "fetch via kernel failed: ${e.message}")
        return errorPage(
            502, "内核转发失败",
            "<p>${e.javaClass.simpleName}: ${e.message ?: "unknown"}</p>" +
                "<p>${request.url}</p>",
        )
    }

    private fun fetch(request: WebResourceRequest, addr: InetSocketAddress): WebResourceResponse? {
        val method = request.method.uppercase()
        if (method != "GET" && method != "HEAD") {
            // shouldInterceptRequest does not expose the request body. Logged
            // because a page whose XHR silently turns into a 501 looks broken
            // to its own script, which is how a site can decide to misbehave.
            Log.w(TAG, "unsupported method $method ${request.url}")
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
        // The kernel's race dial can legitimately take ~20s on a bad address
        // batch; a tight timeout here used to abort work the kernel would have
        // finished, which showed up as a page full of blank/black regions.
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        // An intercepted response is final for the WebView, so redirects have
        // to be followed here; returning the 3xx would leave the page blank.
        conn.instanceFollowRedirects = true

        // Replay cookies the WebView already accumulated (set by previous
        // responses or page script) so subsequent requests keep the session.
        android.webkit.CookieManager.getInstance().getCookie(reqUrl)?.let {
            conn.setRequestProperty("Cookie", it)
        }

        // The kernel can re-encode an HTML body it rewrites, and an intercepted
        // response is not reliably decoded by Chromium, so ask only for what we
        // can inflate here and hand the WebView plain bytes.
        val acceptEncoding = "gzip, deflate"
        // Leaving the fingerprinted headers visible makes upstream disagreements
        // (e.g. Google answering 429 to the WebView but 200 to curl) diagnosable.
        if (request.isForMainFrame) {
            Log.d(
                TAG,
                "main frame hdr " + request.requestHeaders.entries
                    .joinToString("; ") { "${it.key}=${it.value}" },
            )
        }
        for ((k, v) in request.requestHeaders) {
            val key = k.lowercase()
            if (key in STRIP_REQUEST) continue
            if (key == "if-none-match" || key == "if-modified-since") continue // no 304 churn
            if (key == "accept-encoding") continue
            conn.setRequestProperty(k, v)
        }
        conn.setRequestProperty("Accept-Encoding", acceptEncoding)

        // shouldInterceptRequest hands over a trimmed header set: no
        // Accept-Language and no Sec-Fetch-* metadata, which every Chromium
        // navigation carries.
        if (!request.requestHeaders.keys.any { it.equals("Accept-Language", true) }) {
            conn.setRequestProperty("Accept-Language", localeHeader())
        }
        if (request.isForMainFrame &&
            !request.requestHeaders.keys.any { it.startsWith("Sec-Fetch-", true) }
        ) {
            conn.setRequestProperty("Sec-Fetch-Site", "none")
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            conn.setRequestProperty("Sec-Fetch-Dest", "document")
            conn.setRequestProperty("Sec-Fetch-User", "?1")
        }

        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = tls.first
            conn.hostnameVerifier = tls.second
        }

        val code = conn.responseCode
        if (request.isForMainFrame) {
            // conn.url is the URL after redirects; Google answers 429 on the
            // .hk hop, not on the .com one that the WebView asked for.
            Log.d(TAG, "main frame done ${request.url} -> $code (upstream ${conn.url})")
        }
        val rawStream = if (code in 400..599) conn.errorStream ?: conn.inputStream else conn.inputStream
            ?: ByteArrayInputStream(ByteArray(0))
        val encoding = (conn.contentEncoding ?: "").lowercase().trim()
        val decoded = encoding == "gzip" || encoding == "x-gzip" || encoding == "deflate"
        val stream = when {
            encoding == "gzip" || encoding == "x-gzip" -> GZIPInputStream(rawStream)
            encoding == "deflate" -> InflaterInputStream(rawStream)
            else -> rawStream
        }

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
            // The body is already inflated, so its framing headers are stale.
            .filterKeys { !decoded || it.lowercase() !in setOf("content-encoding", "content-length") }
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

    /** "zh-CN,zh;q=0.9" from the device locale, matching Chromium's format. */
    private fun localeHeader(): String {
        val tag = Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
        val base = tag.substringBefore('-')
        return if (base == tag) tag else "$tag,$base;q=0.9"
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

    companion object {
        private const val TAG = "ds-fetch"

        private val tls = KernelTrust.setup()

        /** Headers the HTTP client has to own itself. */
        private val STRIP_REQUEST = setOf(
            "host", "connection", "content-length",
            "upgrade", "proxy-connection", "te", "trailer", "transfer-encoding",
        )

        /** Response headers the WebView must not receive verbatim: the stream
         * we hand over is already framed by WebResourceResponse. */
        private val STRIP_RESPONSE =
            setOf("connection", "transfer-encoding", "keep-alive", "proxy-connection")
    }
}


