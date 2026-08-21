package com.cone.agent.mcp

import org.junit.Assert.*
import org.junit.Test

class McpServerConfigTest {
    @Test fun validConfig() {
        val c = McpServerConfig(name = "test", url = "https://example.com/mcp")
        assertTrue(c.validate().isSuccess)
    }
    @Test fun blankNameFails() {
        val c = McpServerConfig(name = "", url = "https://example.com/mcp")
        assertTrue(c.validate().isFailure)
    }
    @Test fun blankUrlFails() {
        val c = McpServerConfig(name = "a", url = "")
        assertTrue(c.validate().isFailure)
    }
    @Test fun httpUrlValid() {
        val c = McpServerConfig(name = "a", url = "http://localhost:3000/sse")
        assertTrue(c.validate().isSuccess)
    }
    @Test fun invalidSchemeFails() {
        val c = McpServerConfig(name = "a", url = "ftp://example.com")
        assertTrue(c.validate().isFailure)
    }
    @Test fun serializationRoundTrip() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val c = McpServerConfig(name = "demo", url = "https://demo.com/mcp", transport = McpTransport.HTTP)
        val raw = json.encodeToString(McpServerConfig.serializer(), c)
        val decoded = json.decodeFromString(McpServerConfig.serializer(), raw)
        assertEquals(c.name, decoded.name)
        assertEquals(c.url, decoded.url)
        assertEquals(c.transport, decoded.transport)
    }
    @Test fun transportFrom() {
        assertEquals(McpTransport.SSE, McpTransport.from("sse"))
        assertEquals(McpTransport.HTTP, McpTransport.from("http"))
        assertEquals(McpTransport.SSE, McpTransport.from("unknown"))
    }
}
