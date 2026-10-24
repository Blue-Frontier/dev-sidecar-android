package com.bluefrontier.dscore

import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Keeps navigation inside this WebView and trusts exactly one issuer:
 * the kernel CA. Every other certificate (legit upstream or a MITM that is
 * not ours) is rejected.
 */
class KernelCaWebViewClient : WebViewClient() {

    companion object {
        private const val TAG = "ds-web"

        /** Must match certcache's CA subject CN. */
        const val KERNEL_CA_ISSUER = "dev-sidecar-core CA"
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val issuer = error.certificate?.issuedBy?.toString().orEmpty()
        if (issuer.contains(KERNEL_CA_ISSUER)) {
            Log.i(TAG, "trust kernel-issued certificate for ${error.url}")
            handler.proceed()
            return
        }
        Log.w(TAG, "reject untrusted certificate issuer=$issuer url=${error.url}")
        handler.cancel()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        view.loadUrl(request.url.toString())
        return true
    }
}
