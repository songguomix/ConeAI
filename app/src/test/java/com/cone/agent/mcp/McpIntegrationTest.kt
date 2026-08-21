package com.cone.agent.mcp

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Test fun mcpCallTextParsing() {
        val text = "my_tool {\"query\":\"hello\",\"limit\":5}"
        val brace = text.indexOf('{')
        val name = text.substring(0, brace).trim()
        val argsRaw = text.substring(brace)
        assertEquals("my_tool", name)
        val parsed = json.parseToJsonElement(argsRaw)
        assertTrue(parsed is kotlinx.serialization.json.JsonObject)
    }

    @Test fun promptSectionFormat() {
        val tools = listOf(
            McpTool("s1", "ServerA", "tool1", "does X"),
            McpTool("s1", "ServerA", "tool2", null),
            McpTool("s2", "ServerB", "another", "another tool"),
        )
        val section = buildString {
            appendLine("【MCP 工具】已连接 ${tools.distinctBy { it.serverId }.size} 个 MCP 服务，可用工具：")
            tools.forEach { appendLine(it.promptLine()) }
        }
        assertTrue(section.contains("tool1"))
        assertTrue(section.contains("ServerA"))
        assertTrue(section.contains("2 个 MCP 服务"))
    }

    @Test fun jsonRpcRoundTrip() {
        val req = JsonRpcRequest(id = 1, method = "tools/call", params = buildJsonObject { put("name", "my_tool") })
        val raw = json.encodeToString(JsonRpcRequest.serializer(), req)
        val decoded = json.decodeFromString(JsonRpcRequest.serializer(), raw)
        assertEquals(req.method, decoded.method)
        assertEquals(req.id, decoded.id)
    }
}
