package com.cone.agent.remote

import android.content.Context
import com.cone.agent.R
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * New LAN links pin the desktop installation's certificate using the SHA-256 in its QR code.
 * Legacy links retain the bundled certificate fallback. Public tunnels use normal CA validation
 * because the tunnel, rather than the desktop, terminates TLS.
 */
@Singleton
class RemoteTls @Inject constructor(@ApplicationContext private val context: Context) {

    private val pinnedCert: X509Certificate? by lazy { loadPinnedCert() }
    private val trustManager: X509TrustManager by lazy { buildTrustManager() }

    /** Apply the composite trust + pinned-host verifier to a client builder. No-op if the cert is missing. */
    fun configure(builder: OkHttpClient.Builder, fingerprint: String? = null): OkHttpClient.Builder {
        if (fingerprint != null) {
            val pinned = FingerprintTrustManager(fingerprint)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(pinned), SecureRandom())
            }
            return builder.sslSocketFactory(sslContext.socketFactory, pinned).hostnameVerifier { _, session ->
                runCatching {
                    val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                    leaf != null && certificateMatchesFingerprint(leaf, fingerprint)
                }.getOrDefault(false)
            }
        }
        val cert = pinnedCert ?: return builder
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        val default = HttpsURLConnection.getDefaultHostnameVerifier()
        builder.hostnameVerifier(
            HostnameVerifier { hostname, session ->
                // Normal verification first (covers tunnels with real certs / valid SANs).
                if (default.verify(hostname, session)) return@HostnameVerifier true
                // Otherwise only accept the hostname mismatch when the server is OUR pinned cert.
                runCatching { session.peerCertificates.firstOrNull() as? X509Certificate == cert }
                    .getOrDefault(false)
            },
        )
        return builder
    }

    private fun loadPinnedCert(): X509Certificate? = runCatching {
        context.resources.openRawResource(R.raw.conecode_cert).use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
        }
    }.getOrNull()

    private fun buildTrustManager(): X509TrustManager {
        val system = trustManagerFor(null)
        val pinned = pinnedCert?.let { cert ->
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("conecode", cert)
            }
            trustManagerFor(keyStore)
        }
        return if (pinned == null) system else CompositeTrustManager(system, pinned)
    }

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Trusts a server cert if EITHER the system CAs OR our pinned self-signed cert validate it. */
    private class CompositeTrustManager(
        private val system: X509TrustManager,
        private val pinned: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (_: CertificateException) {
                pinned.checkServerTrusted(chain, authType)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers + pinned.acceptedIssuers
    }
}

internal fun certificateMatchesFingerprint(certificate: X509Certificate, fingerprint: String): Boolean {
    if (!fingerprint.matches(Regex("[0-9a-fA-F]{64}"))) return false
    val expected = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
}

internal class FingerprintTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
        leaf.checkValidity()
        if (!certificateMatchesFingerprint(leaf, fingerprint)) throw CertificateException("Desktop certificate fingerprint mismatch")
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not accepted")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
