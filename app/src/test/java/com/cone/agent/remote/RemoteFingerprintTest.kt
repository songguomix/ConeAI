package com.cone.agent.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class RemoteFingerprintTest {
    private val certificate = File("src/main/res/raw/conecode_cert.pem").inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }

    @Test fun matchesOnlyThePinnedCertificate() {
        assertTrue(certificateMatchesFingerprint(certificate, fingerprint))
        assertTrue(certificateMatchesFingerprint(certificate, fingerprint.uppercase()))
        assertFalse(certificateMatchesFingerprint(certificate, "00".repeat(32)))
        assertFalse(certificateMatchesFingerprint(certificate, "bad"))
        FingerprintTrustManager(fingerprint).checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test(expected = CertificateException::class)
    fun rejectsAChangedDesktopCertificate() {
        FingerprintTrustManager("00".repeat(32)).checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test(expected = CertificateException::class)
    fun rejectsMissingCertificateChain() {
        FingerprintTrustManager(fingerprint).checkServerTrusted(emptyArray(), "RSA")
    }
}
