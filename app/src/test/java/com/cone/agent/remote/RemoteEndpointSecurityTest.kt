package com.cone.agent.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing-URL policy: the token travels in the query string, so plain http is only tolerated on
 * addresses that cannot leave a trusted LAN. Everything else must be https.
 */
class RemoteEndpointSecurityTest {

    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `https pairing link is accepted and marked secure`() {
        val endpoint = RemoteEndpoint.parse("https://desk.example.com:8723/?t=$token")
        assertNotNull(endpoint)
        assertFalse(endpoint!!.insecure)
        assertEquals(token, endpoint.token)
        // The UI label must never contain the token.
        assertFalse(endpoint.display.contains(token))
    }

    @Test
    fun `public http is rejected`() {
        assertNull(RemoteEndpoint.parse("http://8.8.8.8:8723/?t=$token"))
        assertNull(RemoteEndpoint.parse("http://example.com:8723/?t=$token"))
    }

    @Test
    fun `private http is allowed but flagged insecure`() {
        listOf(
            "http://192.168.1.23:8723",
            "http://10.0.0.5:8723",
            "http://172.16.4.9:8723",
            "http://127.0.0.1:8723",
            "http://localhost:8723",
            "http://my-mac.local:8723",
        ).forEach { base ->
            val endpoint = RemoteEndpoint.parse("$base/?t=$token")
            assertNotNull("expected $base to parse", endpoint)
            assertTrue("expected $base to be flagged insecure", endpoint!!.insecure)
        }
    }

    @Test
    fun `a link without a token is rejected`() {
        assertNull(RemoteEndpoint.parse("https://desk.example.com:8723/"))
        assertNull(RemoteEndpoint.parse("not a url"))
    }

    @Test
    fun `error text keeps tokens out of sight`() {
        val raw = "failed: https://desk.example.com:8723/?t=$token&p=hunter2"
        val safe = redactSecret(raw)
        assertFalse(safe.contains(token))
        assertFalse(safe.contains("hunter2"))
        assertTrue(safe.contains("redacted"))
    }
    @Test
    fun `pairing fingerprint is retained and malformed pins are rejected`() {
        val pin = "AB".repeat(32)
        val endpoint = RemoteEndpoint.parse("https://192.168.1.2:8723/?t=$token&fp=$pin")!!
        assertEquals(pin.lowercase(), endpoint.fingerprint)
        assertNull(RemoteEndpoint.parse("https://192.168.1.2:8723/?t=$token&fp=bad"))
        assertNull(RemoteEndpoint.parse("https://192.168.1.2:8723/?t=$token&fp=${"gg".repeat(32)}"))
        assertNull(RemoteEndpoint.parse("http://192.168.1.2:8723/?t=$token&fp=$pin"))
        assertFalse(endpoint.display.contains(pin, ignoreCase = true))
    }

    @Test
    fun `https IPv6 pairing retains valid URL brackets`() {
        assertEquals("https://[::1]:8723", RemoteEndpoint.parse("https://[::1]:8723/?t=$token")!!.base)
    }

    @Test
    fun `UU binding identity follows the verified pin not the LAN address`() {
        val first = RemoteEndpoint("https://192.168.1.2:8723", "one", fingerprint = "aa".repeat(32))
        val moved = RemoteEndpoint("https://192.168.1.3:8723", "two", fingerprint = "AA".repeat(32))
        assertEquals(remoteBindingIdentity(first), remoteBindingIdentity(moved))
        assertFalse(remoteBindingIdentity(first) == remoteBindingIdentity(first.copy(fingerprint = "bb".repeat(32))))
    }

    @Test
    fun `unpinned UU binding identities cannot be claimed by another origin or credential`() {
        val first = RemoteEndpoint("https://first.example.com", "one")
        assertFalse(remoteBindingIdentity(first) == remoteBindingIdentity(first.copy(base = "https://other.example.com")))
        assertFalse(remoteBindingIdentity(first) == remoteBindingIdentity(first.copy(token = "two")))
    }

}
