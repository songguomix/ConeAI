package com.cone.agent.mcp

import kotlinx.serialization.Serializable
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers

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
    val transport: McpTransport = McpTransport.HTTP,
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val description: String = "",
) {
    fun validate(): Result<Unit> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("name blank"))
        val u = url.trim()
        if (u.isBlank()) return Result.failure(IllegalArgumentException("url blank"))
        if (u.toHttpUrlOrNull() == null) {
            return Result.failure(IllegalArgumentException("url must be http(s)"))
        }
        if (transport != McpTransport.HTTP) return Result.failure(IllegalArgumentException("Use a Streamable HTTP MCP endpoint; legacy SSE and stdio are not supported."))
        runCatching { Headers.Builder().apply { headers.forEach { (key, value) -> add(key, value) } }.build() }
            .getOrElse { return Result.failure(IllegalArgumentException("Invalid HTTP headers")) }
        return Result.success(Unit)
    }

    fun displayUrl(): String = url.trim()

    companion object {
        fun generateId(): String = UUID.randomUUID().toString()
    }
}
