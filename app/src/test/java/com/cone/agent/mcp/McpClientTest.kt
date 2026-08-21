package com.cone.agent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class McpClientTest {
    @Test fun fakeClientListTools() = runTest {
        val tools = listOf(McpToolDefinition(name = "tool1", description = "desc1"))
        val c = FakeMcpClient(tools = tools)
        val res = c.listTools().getOrThrow()
        assertEquals(1, res.size)
        assertEquals("tool1", res[0].name)
    }
    @Test fun fakeClientCallTool() = runTest {
        val c = FakeMcpClient(callHandler = { name, _ -> "result:$name" })
        val r = c.callTool("my_tool", buildJsonObject { put("a", 1) }).getOrThrow()
        assertEquals("result:my_tool", r)
    }
    @Test fun fakeClientPing() = runTest {
        val c = FakeMcpClient()
        assertTrue(c.ping().isSuccess)
    }
    @Test fun fakeClientInitialize() = runTest {
        val c = FakeMcpClient()
        assertTrue(c.initialize().isSuccess)
    }
}
