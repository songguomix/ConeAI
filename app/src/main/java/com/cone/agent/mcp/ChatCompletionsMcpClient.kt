package com.cone.agent.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ChatCompletionsMcpClient(private val config: McpServerConfig, private val okHttp: OkHttpClient, private val json: Json) : McpClient {
    private val endpoint = config.url.trim()
    override suspend fun initialize(): Result<McpInitializeResult> = Result.success(McpInitializeResult())
    override suspend fun listTools(): Result<List<McpToolDefinition>> = runCatching {
        val tools = fetchChatTools().getOrNull() ?: listOf(McpToolDefinition(name = "chat", description = "chat/completions proxy for ${config.name}", inputSchema = buildJsonObject { put("type","object") }))
        tools
    }
    private suspend fun fetchChatTools(): Result<List<McpToolDefinition>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("model", "gpt-3.5-turbo").put("messages", org.json.JSONArray().put(JSONObject().put("role","user").put("content","list available tools as JSON"))).toString()
            val req = Request.Builder().url(endpoint).post(body.toRequestBody("application/json".toMediaType())).apply { config.headers.forEach { (k,v)-> header(k,v) } }.build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("chat HTTP ${resp.code}")
                val raw = resp.body?.string().orEmpty()
                val content = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                if (content.contains("tool")) listOf(McpToolDefinition(name="chat", description=content.take(200), inputSchema=buildJsonObject{ put("type","object")})) else emptyList()
            }
        }
    }
    override suspend fun callTool(name: String, arguments: JsonElement?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = "Tool $name called with arguments ${arguments?.toString() ?: "{}"} on server ${config.name}. Respond with result."
            val body = JSONObject().put("model","gpt-3.5-turbo").put("messages", org.json.JSONArray().put(JSONObject().put("role","user").put("content",prompt))).toString()
            val req = Request.Builder().url(endpoint).post(body.toRequestBody("application/json".toMediaType())).apply { config.headers.forEach { (k,v)-> header(k,v) } }.build()
            okHttp.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${raw.take(500)}")
                JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf{it.isNotBlank()} ?: raw
            }
        }
    }
    override suspend fun ping(): Result<Unit> = Result.success(Unit)
}
