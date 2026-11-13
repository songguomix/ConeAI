package com.cone.agent.remote

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class UuRemoteProtocolTest {
    private val now = 10_000L
    private val pending = UuRemoteProtocol.Pending("unguessable-request", "computer-a", UuRemoteProtocol.Kind.BIND, now)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun query(url: String) = URI(url).rawQuery.split('&').associate {
        val fields = it.split('=', limit = 2)
        URLDecoder.decode(fields[0], "UTF-8") to URLDecoder.decode(fields[1], "UTF-8")
    }
    private fun bound(id: String = "opaque+/=id") = "coneai-uu://bind?anonymous_device_id=${encode(id)}&nonce=${pending.nonce}"

    @Test fun authorizationCarriesEncodedCallbackAndNonce() {
        val url = UuRemoteProtocol.bindUrl(pending.nonce)
        assertEquals("uuremote://external/device/authorize", url.substringBefore('?'))
        assertEquals("coneai-uu://bind", query(url)["callback"])
        assertEquals(pending.nonce, query(url)["nonce"])
    }
    @Test fun opaqueDeviceIdIsNotRewrittenAndFullscreenDoesNotWaitForCallback() {
        val id = "encrypted+/= value&x=1"
        val url = UuRemoteProtocol.controlUrl(id, UuRemoteProtocol.Mode.FULLSCREEN, pending.nonce)
        assertEquals(id, query(url)["anonymous_device_id"])
        assertEquals("0", query(url)["window_type"])
        assertFalse(query(url).containsKey("callback"))
    }
    @Test fun pipUsesWindowTypeOneAndPreservesRequestIdInsideItsCallback() {
        val url = UuRemoteProtocol.controlUrl("id", UuRemoteProtocol.Mode.PIP, pending.nonce)
        assertEquals("1", query(url)["window_type"])
        val callback = query(url).getValue("callback")
        assertEquals("coneai-uu://control", callback.substringBefore('?'))
        assertEquals(pending.nonce, query(callback)["request_id"])
    }
    @Test fun supportsOnlyTheDocumentedMinimumVersionAndNewer() {
        listOf("4.42.0", "4.47.0", "4.100.0", "5.0.0").forEach { assertTrue(it, UuRemoteProtocol.supportedVersion(it)) }
        listOf(null, "unknown", "4.41.99", "3.99.0").forEach { assertFalse(it, UuRemoteProtocol.supportedVersion(it)) }
    }
    @Test fun successfulBindReturnsTheOriginalEncryptedIdentifier() {
        assertEquals("opaque+/=id", UuRemoteProtocol.parseCallback(bound(), pending, now)?.deviceId)
    }
    @Test fun bindErrorsAlwaysKeepNonceValidation() {
        assertEquals("user_cancel", UuRemoteProtocol.parseCallback("coneai-uu://bind?error=user_cancel&nonce=${pending.nonce}", pending, now)?.error)
        assertNull(UuRemoteProtocol.parseCallback("coneai-uu://bind?error=user_cancel&nonce=wrong", pending, now))
    }
    @Test fun rejectsUnsolicitedWrongOriginDuplicateAndAmbiguousCallbacks() {
        listOf(
            bound().replace(pending.nonce, "wrong"), bound().replace("coneai-uu", "https"),
            bound().replace("://bind", "://control"), bound() + "&nonce=other",
            bound() + "&error=user_cancel", bound().replace("://bind?", "://bind/other?"),
            bound().replace("://bind?", "://user@bind?"), bound().replace("://bind?", "://bind:88?"),
            bound() + "#fragment", "coneai-uu://bind?anonymous_device_id=x", "coneai-uu://bind?nonce=${pending.nonce}",
        ).forEach { assertNull(it, UuRemoteProtocol.parseCallback(it, pending, now)) }
    }
    @Test fun rejectsExpiredAndFutureRequestsWithoutImposingAShortPipTimeout() {
        assertNull(UuRemoteProtocol.parseCallback(bound(), pending, now - 1))
        assertNull(UuRemoteProtocol.parseCallback(bound(), pending, now + UuRemoteProtocol.MAX_REQUEST_AGE_MS + 1))
        assertNotNull(UuRemoteProtocol.parseCallback(bound(), pending, now + 10 * 60 * 1000))
    }
    @Test fun rejectsEmptyOverlongAndControlCharacterIdentifiers() {
        listOf("", "\nsecret", "x".repeat(8193)).forEach { assertNull(UuRemoteProtocol.parseCallback(bound(it), pending, now)) }
    }
    @Test fun pipSuccessAndDeviceNotFoundAreExplicitResults() {
        val control = pending.copy(kind = UuRemoteProtocol.Kind.CONTROL, deviceId = "id")
        val prefix = "coneai-uu://control?request_id=${pending.nonce}"
        assertTrue(UuRemoteProtocol.parseCallback("$prefix&status=success", control, now)!!.pipReady)
        assertEquals("device_not_found", UuRemoteProtocol.parseCallback("$prefix&status=failure&reason=device_not_found", control, now)?.error)
        assertNull(UuRemoteProtocol.parseCallback("$prefix&status=failure", control, now))
        assertNull(UuRemoteProtocol.parseCallback("$prefix&status=success&reason=connect_failed", control, now))
        assertNull(UuRemoteProtocol.parseCallback("$prefix&status=success", pending, now))
    }
    @Test fun unknownReasonsBecomeSafeGenericErrors() {
        val control = pending.copy(kind = UuRemoteProtocol.Kind.CONTROL)
        assertEquals("connect_failed", UuRemoteProtocol.parseCallback("coneai-uu://control?request_id=${pending.nonce}&status=failure&reason=unknown", control, now)?.error)
    }
    @Test fun malformedAndDuplicateEncodedKeysAreRejected() {
        assertNull(UuRemoteProtocol.requestId("coneai-uu://bind?nonce=%GG"))
        assertNull(UuRemoteProtocol.requestId("coneai-uu://bind?nonce=a&%6eonce=b"))
        assertEquals(pending.nonce, UuRemoteProtocol.requestId(bound()))
    }
}
