package org.blueFrontier.devSideCar.next.android

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The CN the in-process kernel puts on the CA it signs site certificates with. */
const val KERNEL_CA_ISSUER = "dev-sidecar-core CA"

/**
 * Builds an SSL factory that accepts kernel-issued chains while still verifying
 * everything else against the system store. The kernel MITMs upstream TLS, so
 * every intercepted site presents a chain the system does not know.
 */
object KernelTrust {

    fun setup(): Pair<SSLSocketFactory, HostnameVerifier> {
        val systemTm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                systemTm.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    systemTm.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    val kernelIssued = chain.orEmpty().any { cert ->
                        cert.issuerX500Principal.name.contains(KERNEL_CA_ISSUER) ||
                            cert.issuerDN.name.contains(KERNEL_CA_ISSUER)
                    }
                    if (!kernelIssued) throw e
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers
        }

        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tm), null)
        }
        val verifier = HostnameVerifier { host, session ->
            if (HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                true
            } else {
                session.peerCertificates.any { c ->
                    (c as? X509Certificate)?.issuerDN?.name?.contains(KERNEL_CA_ISSUER) == true
                }
            }
        }
        return ctx.socketFactory to verifier
    }
}
