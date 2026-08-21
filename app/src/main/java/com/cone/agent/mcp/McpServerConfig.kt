package com.cone.agent.mcp

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class McpTransport(val wire: String) {
    SSE("sse"),
    HTTP("http"),
    STDIO("stdio");

    companion object {
        fun from(v: String?): McpTransport =
            entries.firstOrNull { it.wire.equals(v?.trim(), true) } ?: SSE
    }
}

@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val transport: McpTransport = McpTransport.SSE,
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val description: String = "",
) {
    fun validate(): Result<Unit> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("name blank"))
        val u = url.trim()
        if (u.isBlank()) return Result.failure(IllegalArgumentException("url blank"))
        if (!u.startsWith("http://") && !u.startsWith("https://") && transport != McpTransport.STDIO) {
            return Result.failure(IllegalArgumentException("url must be http(s)"))
        }
        return Result.success(Unit)
    }

    fun displayUrl(): String = url.trim()

    companion object {
        fun generateId(): String = UUID.randomUUID().toString()
    }
}
