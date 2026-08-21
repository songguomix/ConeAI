package com.cone.agent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

data class McpTool(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null,
) {
    fun promptLine(): String = buildString {
        append("- $name")
        serverName.takeIf { it.isNotBlank() }?.let { append(" (via $it)") }
        description?.takeIf { it.isNotBlank() }?.let { append(": $it") }
        inputSchema?.let { schema ->
            val props = (schema["properties"] as? JsonObject)?.keys?.joinToString(", ")
            if (!props.isNullOrBlank()) append(" | params: {$props}")
        }
    }
}

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonElement? = null,
)
