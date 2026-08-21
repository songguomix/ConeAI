package com.cone.agent.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class McpInitializeParams(
    @SerialName("protocolVersion") val protocolVersion: String = "2024-11-05",
    val capabilities: JsonObject = buildJsonObject {},
    @SerialName("clientInfo") val clientInfo: McpClientInfo = McpClientInfo(),
)

@Serializable
data class McpClientInfo(
    val name: String = "ConeAI",
    val version: String = "1.0",
)

@Serializable
data class McpInitializeResult(
    @SerialName("protocolVersion") val protocolVersion: String? = null,
    val capabilities: JsonObject? = null,
    @SerialName("serverInfo") val serverInfo: JsonObject? = null,
)

@Serializable
data class ListToolsResult(
    val tools: List<McpToolDefinition> = emptyList(),
)

@Serializable
data class CallToolParams(
    val name: String,
    val arguments: JsonElement? = null,
)

@Serializable
data class CallToolResult(
    val content: List<McpContent> = emptyList(),
    @SerialName("isError") val isError: Boolean? = null,
)

@Serializable
data class McpContent(
    val type: String,
    val text: String? = null,
)

object McpMethods {
    const val INITIALIZE = "initialize"
    const val PING = "ping"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val RESOURCES_LIST = "resources/list"
    const val PROMPTS_LIST = "prompts/list"
    const val NOTIFICATION_INITIALIZED = "notifications/initialized"
}

object McpProtocol {
    private var idCounter = 1L
    fun nextId(): Long = synchronized(this) { idCounter++ }

    fun request(method: String, params: JsonElement? = null): JsonRpcRequest =
        JsonRpcRequest(id = nextId(), method = method, params = params)

    fun isSuccess(resp: JsonRpcResponse): Boolean = resp.error == null

    fun extractText(result: CallToolResult): String =
        result.content.mapNotNull { it.text }.joinToString("\n").ifBlank { result.content.toString() }

    fun toolCallResultToString(element: JsonElement, json: kotlinx.serialization.json.Json): String {
        val parsed = runCatching { json.decodeFromJsonElement(CallToolResult.serializer(), element) }.getOrNull()
        if (parsed != null && parsed.content.isNotEmpty()) return extractText(parsed)
        return element.toString().take(4000)
    }
}
