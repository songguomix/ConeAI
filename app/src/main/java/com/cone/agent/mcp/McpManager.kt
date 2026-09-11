package com.cone.agent.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class McpManager @Inject constructor(
    private val repository: McpRepository,
    private val okHttp: OkHttpClient,
    private val json: Json,
) {
    private val _tools = MutableStateFlow<List<McpTool>>(emptyList())
    val toolsFlow: StateFlow<List<McpTool>> = _tools
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val refreshMutex = Mutex()
    private val clientMutex = Mutex()
    private val clients = mutableMapOf<McpServerConfig, McpClient>()

    val serversFlow: Flow<List<McpServerConfig>> = repository.serversFlow

    suspend fun getServers(): List<McpServerConfig> = repository.getServers()

    fun cachedTools(): List<McpTool> = _tools.value

    fun promptSection(): String {
        val tools = _tools.value
        if (tools.isEmpty()) return ""
        return buildString {
            appendLine("\n【MCP 工具】已连接 ${tools.distinctBy { it.serverId }.size} 个 MCP 服务，可用工具：")
            tools.forEach { appendLine(it.promptLine()) }
            appendLine("调用方式：输出 {\"action\":\"mcp_call\",\"app\":\"serverId\",\"text\":\"toolName {\\\"arg\\\":value}\"}，")
            appendLine("或问答中输出 {\"tool\":\"mcp_call\",\"query\":\"toolName {\\\"arg\\\":value}\",\"app\":\"serverId\"}。")
            appendLine("参数为 JSON 对象，未知则传 {}。执行结果会以【工具结果】回传。")
        }
    }

    suspend fun addServer(config: McpServerConfig): Result<Unit> = repository.addServer(config)

    suspend fun updateServer(config: McpServerConfig): Result<Unit> = repository.updateServer(config)

    suspend fun removeServer(id: String): Result<Unit> {
        val r = repository.removeServer(id)
        if (r.isSuccess) _tools.value = _tools.value.filterNot { it.serverId == id }
        return r
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> = repository.setEnabled(id, enabled)

    suspend fun testConnection(config: McpServerConfig): Result<List<McpToolDefinition>> {
        config.validate().getOrElse { return Result.failure(it) }
        val client = createClient(config)
        client.initialize().getOrElse { return Result.failure(it) }
        return client.listTools()
    }

    suspend fun refreshAll(): Result<Unit> = refreshMutex.withLock {
        _refreshing.value = true
        try {
            val servers = repository.getServers().filter { it.enabled }
            clientMutex.withLock { clients.keys.retainAll(servers.toSet()) }
            _tools.value = emptyList()
            _errors.value = emptyMap()
            for (s in servers) {
                try {
                    val tools = connectedClient(s).listTools().getOrThrow()
                    _tools.value += tools.map { def ->
                        McpTool(s.id, s.name, def.name, def.description, def.inputSchema as? JsonObject)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errors.value += (s.id to (e.message ?: "Connection failed"))
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _refreshing.value = false
        }
    }

    suspend fun callTool(serverId: String?, toolName: String, arguments: JsonElement?): Result<String> {
        val servers = repository.getServers()
        val target: McpServerConfig? = when {
            serverId != null -> servers.firstOrNull { it.id == serverId || it.name == serverId }
            servers.size == 1 -> servers.firstOrNull()
            else -> {
                val withTool = _tools.value.firstOrNull { it.name == toolName }
                if (withTool != null) servers.firstOrNull { it.id == withTool.serverId }
                else servers.firstOrNull { it.enabled }
            }
        }
        if (target == null) return Result.failure(IllegalArgumentException("MCP server not found: $serverId"))
        if (!target.enabled) return Result.failure(IllegalStateException("server disabled"))
        return try {
            connectedClient(target).callTool(toolName, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun callToolRaw(serverId: String?, rawText: String?): Result<String> {
        val text = rawText?.trim().orEmpty()
        if (text.isBlank()) return Result.failure(IllegalArgumentException("tool name missing"))
        val name: String
        val args: JsonElement?
        val firstSpace = text.indexOf(' ')
        val firstBrace = text.indexOf('{')
        when {
            firstBrace >= 0 -> {
                name = text.substring(0, firstBrace).trim().ifBlank { text.substringBefore('{').trim() }
                val jsonPart = text.substring(firstBrace).trim()
                args = runCatching { json.parseToJsonElement(jsonPart) }.getOrNull()
            }
            firstSpace >= 0 -> {
                name = text.substring(0, firstSpace).trim()
                val rest = text.substring(firstSpace + 1).trim()
                args = if (rest.startsWith("{")) runCatching { json.parseToJsonElement(rest) }.getOrNull() else JsonPrimitive(rest)
            }
            else -> {
                name = text
                args = null
            }
        }
        if (name.isBlank()) return Result.failure(IllegalArgumentException("tool name missing"))
        return callTool(serverId, name, args)
    }

    private fun createClient(config: McpServerConfig): McpClient {
        if (config.url.contains("chat/completions", true)) return ChatCompletionsMcpClient(config, okHttp, json)
        return HttpMcpClient(config, okHttp, json)
    }

    private suspend fun connectedClient(config: McpServerConfig): McpClient = clientMutex.withLock {
        config.validate().getOrThrow()
        clients[config] ?: createClient(config).also {
            it.initialize().getOrThrow()
            clients[config] = it
        }
    }

    fun parseAndBuildArgs(text: String?, app: String?): Triple<String?, String, JsonElement?> {
        val tool = text?.trim()?.substringBefore(' ')?.trim()?.ifBlank { null } ?: text?.trim()?.ifBlank { null }
        return Triple(app, tool ?: "", null)
    }
}
