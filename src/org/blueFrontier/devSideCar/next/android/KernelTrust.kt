package org.blueFrontier.devSideCar.next.android

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust for everything that talks to the in-process kernel: API probes
 * (`HttpsURLConnection`) and the WebView's TLS errors.
 *
 * The kernel mints its CA on this device at first start, so the anchor is read
 * from `files/ca/ca.crt` rather than the APK. Each install therefore trusts
 * exactly one authority: its own.
 */
object KernelTrust {

    /** Parsed CA with the file mtime it was read from; the CA changes only once per install. */
    @Volatile
    private var cached: Pair<Long, X509Certificate>? = null

    /** The CA minted on this device, or null before the kernel has ever run. */
    fun caCert(ctx: Context): X509Certificate? {
        val file = KernelRuntime.caCertFile(ctx)
        val stamp = file.lastModified()
        if (stamp == 0L) return null
        cached?.let { (cachedStamp, cert) -> if (cachedStamp == stamp) return cert }
        return runCatching {
            val factory = CertificateFactory.getInstance("X.509")
            file.inputStream().use { factory.generateCertificate(it) as X509Certificate }
        }.getOrNull()?.also { cached = stamp to it }
    }

    fun setup(ctx: Context): Pair<SSLSocketFactory, HostnameVerifier> {
        val managers = mutableListOf(systemTrustManager())
        caCert(ctx)?.let { managers.add(anchorTrustManager(it)) }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(CompositeTrustManager(managers)), null)
        }
        return context.socketFactory to HttpsURLConnection.getDefaultHostnameVerifier()
    }

    /**
     * True when the kernel's CA signed [cert]. The kernel puts the requested
     * host in the leaf's SAN, so a hostname mismatch is never a kernel chain.
     */
    fun isKernelIssued(ctx: Context, cert: X509Certificate?, hostnameMismatch: Boolean = false): Boolean {
        if (cert == null || hostnameMismatch) return false
        val ca = caCert(ctx) ?: return false
        return runCatching { cert.verify(ca.publicKey); true }.getOrDefault(false)
    }

    private fun systemTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /** Trusts exactly one certificate: the CA the kernel minted here. */
    private fun anchorTrustManager(ca: X509Certificate): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("kernel-ca", ca)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    /** System certificates first, then the kernel CA; a chain only has to satisfy one. */
    private class CompositeTrustManager(
        private val delegates: List<X509TrustManager>,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            for (delegate in delegates) {
                try {
                    delegate.checkClientTrusted(chain, authType)
                    return
                } catch (_: CertificateException) {
                }
            }
            throw CertificateException("no trust anchor accepted the client chain")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            var last: CertificateException? = null
            for (delegate in delegates) {
                try {
                    delegate.checkServerTrusted(chain, authType)
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("no trust anchor accepted the server chain")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.asList() }.toTypedArray()
    }
}
