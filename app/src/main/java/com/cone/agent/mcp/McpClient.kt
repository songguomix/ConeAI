package com.cone.agent.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface McpClient {
    suspend fun initialize(): Result<McpInitializeResult>
    suspend fun listTools(): Result<List<McpToolDefinition>>
    suspend fun callTool(name: String, arguments: JsonElement? = null): Result<String>
    suspend fun ping(): Result<Unit>
}

class HttpMcpClient(
    private val config: McpServerConfig,
    private val okHttp: OkHttpClient,
    private val json: Json,
) : McpClient {

    private val endpoint: String get() = config.url.trim().trimEnd('/')
    private val wireJson = Json(json) { encodeDefaults = true }
    private var sessionId: String? = null
    private var protocolVersion: String? = null

    private suspend fun postRpc(request: JsonRpcRequest): Result<JsonRpcResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val bodyStr = wireJson.encodeToString(request)
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
            config.headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) reqBuilder.header(k, v) }
            sessionId?.let { reqBuilder.header("Mcp-Session-Id", it) }
            protocolVersion?.let { reqBuilder.header("MCP-Protocol-Version", it) }
            val req = reqBuilder.build()
            val client = okHttp.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.header("Mcp-Session-Id")?.let { sessionId = it }
                val body = resp.body ?: error("empty response")
                val parsed = if (resp.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                    val source = body.source()
                    val data = StringBuilder()
                    var matched: JsonRpcResponse? = null
                    while (matched == null) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            if (data.isNotEmpty()) {
                                val event = runCatching { json.decodeFromString(JsonRpcResponse.serializer(), data.toString()) }.getOrNull()
                                if (event?.id == request.id) matched = event
                                data.setLength(0)
                            }
                        } else if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }
                    }
                    matched ?: error("No matching MCP response in stream")
                } else parseResponse(body.string())
                check(parsed.id == request.id) { "MCP response ID mismatch" }
                parsed.error?.let { e -> error("RPC ${e.code}: ${e.message}") }
                parsed
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun parseResponse(raw: String): JsonRpcResponse {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) error("empty response")
        if (trimmed.startsWith("event:") || trimmed.contains("\ndata:")) {
            val dataLine = trimmed.lines().firstOrNull { it.trim().startsWith("data:") }
                ?: error("no data in SSE")
            val data = dataLine.substringAfter("data:").trim()
            return json.decodeFromString(JsonRpcResponse.serializer(), data)
        }
        return json.decodeFromString(JsonRpcResponse.serializer(), trimmed)
    }

    override suspend fun initialize(): Result<McpInitializeResult> = runCatching {
        val params = wireJson.encodeToJsonElement(McpInitializeParams.serializer(), McpInitializeParams())
        val req = McpProtocol.request(McpMethods.INITIALIZE, params)
        val resp = postRpc(req).getOrThrow()
        val result = resp.result ?: error("no result")
        val initialized = json.decodeFromJsonElement(McpInitializeResult.serializer(), result)
        protocolVersion = initialized.protocolVersion
        notifyInitialized()
        initialized
    }

    private suspend fun notifyInitialized() = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", McpMethods.NOTIFICATION_INITIALIZED)
        }
        val request = Request.Builder().url(endpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
        config.headers.forEach { (key, value) -> request.header(key, value) }
        sessionId?.let { request.header("Mcp-Session-Id", it) }
        protocolVersion?.let { request.header("MCP-Protocol-Version", it) }
        okHttp.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            .newCall(request.build()).execute().use { check(it.isSuccessful) { "MCP initialization notification HTTP ${it.code}" } }
    }

    override suspend fun listTools(): Result<List<McpToolDefinition>> = runCatching {
        val req = McpProtocol.request(McpMethods.TOOLS_LIST, buildJsonObject {})
        val resp = postRpc(req).getOrThrow()
        val result = resp.result ?: error("no result")
        json.decodeFromJsonElement(ListToolsResult.serializer(), result).tools
    }

    override suspend fun callTool(name: String, arguments: JsonElement?): Result<String> = runCatching {
        val params = buildJsonObject {
            put("name", name)
            arguments?.let { put("arguments", it) }
        }
        val req = McpProtocol.request(McpMethods.TOOLS_CALL, params)
        val resp = postRpc(req).getOrThrow()
        val result = resp.result ?: error("no result")
        McpProtocol.toolCallResultToString(result, json)
    }

    override suspend fun ping(): Result<Unit> = runCatching {
        val req = McpProtocol.request(McpMethods.PING, buildJsonObject {})
        postRpc(req).getOrThrow()
        Unit
    }
}

class FakeMcpClient(
    private val tools: List<McpToolDefinition> = emptyList(),
    private val callHandler: (String, JsonElement?) -> String = { name, _ -> "fake result for $name" },
) : McpClient {
    override suspend fun initialize(): Result<McpInitializeResult> = Result.success(McpInitializeResult())
    override suspend fun listTools(): Result<List<McpToolDefinition>> = Result.success(tools)
    override suspend fun callTool(name: String, arguments: JsonElement?): Result<String> =
        Result.success(callHandler(name, arguments))
    override suspend fun ping(): Result<Unit> = Result.success(Unit)
}
