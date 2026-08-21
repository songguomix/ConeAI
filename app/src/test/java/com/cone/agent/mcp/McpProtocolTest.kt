package com.cone.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class McpProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun requestHasIdAndMethod() {
        val r = McpProtocol.request("tools/list", buildJsonObject {})
        assertTrue(r.id > 0)
        assertEquals("tools/list", r.method)
        assertEquals("2.0", r.jsonrpc)
    }

    @Test fun idsIncrement() {
        val a = McpProtocol.request("ping").id
        val b = McpProtocol.request("ping").id
        assertTrue(b > a)
    }

    @Test fun serializeDeserialize() {
        val req = JsonRpcRequest(id = 42, method = "initialize", params = buildJsonObject { put("foo", "bar") })
        val raw = json.encodeToString(JsonRpcRequest.serializer(), req)
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), raw)
        assertEquals(req.id, decoded.id)
        assertEquals(req.method, decoded.method)
    }

    @Test fun successResponse() {
        val resp = JsonRpcResponse(id = 1, result = buildJsonObject { put("ok", true) })
        assertTrue(McpProtocol.isSuccess(resp))
    }

    @Test fun errorResponse() {
        val resp = JsonRpcResponse(id = 1, error = JsonRpcError( -32601, "Method not found"))
        assertFalse(McpProtocol.isSuccess(resp))
    }

    @Test fun extractText() {
        val result = CallToolResult(content = listOf(McpContent(type = "text", text = "hello"), McpContent(type = "text", text = "world")))
        assertEquals("hello\nworld", McpProtocol.extractText(result))
    }

    @Test fun toolCallResultFallback() {
        val el = buildJsonObject { put("custom", "value") }
        val out = McpProtocol.toolCallResultToString(el, json)
        assertTrue(out.contains("custom"))
    }

    @Test fun listToolsResultParsing() {
        val raw = """{"tools":[{"name":"get_weather","description":"get weather"},{"name":"calc"}]}"""
        val parsed = json.decodeFromString(ListToolsResult.serializer(), raw)
        assertEquals(2, parsed.tools.size)
        assertEquals("get_weather", parsed.tools[0].name)
    }
}
