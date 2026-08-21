package com.cone.agent.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Test

class McpToolTest {
    @Test fun promptLineWithDescription() {
        val t = McpTool("id1", "myServer", "search", "search the web", null)
        val line = t.promptLine()
        assertTrue(line.contains("search"))
        assertTrue(line.contains("myServer"))
        assertTrue(line.contains("search the web"))
    }

    @Test fun promptLineWithSchema() {
        val schema = buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string") }
                putJsonObject("limit") { put("type", "number") }
            }
        }
        val t = McpTool("id1", "srv", "my_tool", null, schema)
        val line = t.promptLine()
        assertTrue(line.contains("query"))
        assertTrue(line.contains("limit"))
    }

    @Test fun promptLineMinimal() {
        val t = McpTool("id", "srv", "empty_tool")
        assertTrue(t.promptLine().contains("empty_tool"))
    }
}
