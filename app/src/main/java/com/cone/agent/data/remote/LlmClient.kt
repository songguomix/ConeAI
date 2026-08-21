package com.cone.agent.data.remote

import com.cone.agent.data.remote.dto.AnthropicContent
import com.cone.agent.data.remote.dto.AnthropicMessage
import com.cone.agent.data.remote.dto.AnthropicRequest
import com.cone.agent.data.remote.dto.ChatMessageDto
import com.cone.agent.data.remote.dto.ChatRequest
import com.cone.agent.data.remote.dto.AnthropicStreamEvent
import com.cone.agent.data.remote.dto.ChatStreamChunk
import com.cone.agent.data.remote.dto.ContentPart
import com.cone.agent.data.remote.dto.StreamOptions
import com.cone.agent.domain.model.ProviderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A model discovered via `GET /models`.
 * [apiVision] reflects what the endpoint advertised: true/false if it declared modalities,
 * or null when the API gave no capability info (caller should fall back to a name heuristic).
 */
data class DiscoveredModel(
    val id: String,
    val apiVision: Boolean?,
    val apiTools: Boolean,
)

/** A chat completion's text plus the total tokens the provider reported (null if not provided). */
data class ChatResult(
    val content: String,
    val totalTokens: Int?,
)

/** One event of a streaming completion: an incremental [Delta] of text, then a final [Done]. */
sealed interface ChatChunk {
    data class Delta(val text: String) : ChatChunk
    data class Done(val totalTokens: Int?) : ChatChunk
}

/** High level wrapper translating provider config into concrete calls for the chosen protocol. */
@Singleton
class LlmClient @Inject constructor(
    private val api: LlmApi,
    private val json: Json,
) {

    /**
     * Streams a completion as [ChatChunk]s (deltas as they arrive, then a [ChatChunk.Done] with token
     * usage). Parses the provider's Server-Sent-Events body line by line. Runs on IO; the [Flow] can
     * be cancelled (e.g. the user hits stop) to abandon the request.
     */
    fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        protocol: ProviderProtocol = ProviderProtocol.OPENAI,
        temperature: Double = 0.2,
        maxTokens: Int = 1500,
    ): Flow<ChatChunk> = flow {
        when (protocol) {
            ProviderProtocol.OPENAI, ProviderProtocol.CUSTOM -> {
                val body = ChatRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    stream = true,
                    streamOptions = StreamOptions(includeUsage = true),
                )
                val url = EndpointResolver.chatUrl(baseUrl, protocol)
                val response = withErrorDetail(url) { api.chatStream(url, authHeader(apiKey), body) }
                var tokens: Int? = null
                response.use { rb ->
                    val source = rb.source()
                    while (true) {
                        val data = sseData(source.readUtf8Line() ?: break) ?: continue
                        if (data == "[DONE]") break
                        val chunk = runCatching { json.decodeFromString(ChatStreamChunk.serializer(), data) }
                            .getOrNull() ?: continue
                        chunk.choices.firstOrNull()?.delta?.content
                            ?.takeIf { it.isNotEmpty() }?.let { emit(ChatChunk.Delta(it)) }
                        chunk.usage?.totalTokens?.let { tokens = it }
                    }
                }
                emit(ChatChunk.Done(tokens))
            }
            ProviderProtocol.ANTHROPIC -> {
                val (system, convo) = toAnthropic(messages)
                val body = AnthropicRequest(
                    model = model,
                    maxTokens = maxTokens,
                    messages = convo,
                    system = system,
                    temperature = temperature,
                    stream = true,
                )
                val url = EndpointResolver.chatUrl(baseUrl, ProviderProtocol.ANTHROPIC)
                val response = withErrorDetail(url) {
                    api.anthropicChatStream(url, anthropicHeaders(apiKey), body)
                }
                var input = 0
                var output = 0
                response.use { rb ->
                    val source = rb.source()
                    while (true) {
                        val data = sseData(source.readUtf8Line() ?: break) ?: continue
                        val ev = runCatching { json.decodeFromString(AnthropicStreamEvent.serializer(), data) }
                            .getOrNull() ?: continue
                        when (ev.type) {
                            "content_block_delta" ->
                                ev.delta?.text?.takeIf { it.isNotEmpty() }?.let { emit(ChatChunk.Delta(it)) }
                            "message_start" -> ev.message?.usage?.inputTokens?.let { input = it }
                            "message_delta" -> ev.usage?.outputTokens?.let { output = it }
                        }
                    }
                }
                emit(ChatChunk.Done((input + output).takeIf { it > 0 }))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Extracts the payload of an SSE `data:` line; null for blank / `event:` / comment lines. */
    private fun sseData(line: String): String? =
        if (line.startsWith("data:")) line.substring(5).trim().ifEmpty { null } else null

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        protocol: ProviderProtocol = ProviderProtocol.OPENAI,
        temperature: Double = 0.2,
        maxTokens: Int = 1500,
    ): Result<ChatResult> = runCatching {
        when (protocol) {
            // CUSTOM shares OpenAI's wire format; only its URL handling differs (resolved per [protocol]).
            ProviderProtocol.OPENAI, ProviderProtocol.CUSTOM ->
                openAiChat(baseUrl, apiKey, model, messages, protocol, temperature, maxTokens)
            ProviderProtocol.ANTHROPIC -> anthropicChat(baseUrl, apiKey, model, messages, temperature, maxTokens)
        }
    }

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol = ProviderProtocol.OPENAI,
    ): Result<List<DiscoveredModel>> = runCatching {
        val url = EndpointResolver.modelsUrl(baseUrl, protocol)
        val response = withErrorDetail(url) {
            when (protocol) {
                ProviderProtocol.ANTHROPIC -> api.anthropicModels(url, anthropicHeaders(apiKey))
                else -> api.models(url, authHeader(apiKey))
            }
        }
        response.all()
            .mapNotNull { dto ->
                val id = dto.resolveId().ifBlank { null } ?: return@mapNotNull null
                DiscoveredModel(id = id, apiVision = dto.apiAdvertisesVision(), apiTools = dto.apiAdvertisesTools())
            }
            .distinctBy { it.id }
            .sortedBy { it.id }
    }

    /* ---------------- OpenAI-compatible ---------------- */

    private suspend fun openAiChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        protocol: ProviderProtocol,
        temperature: Double,
        maxTokens: Int,
    ): ChatResult {
        val url = EndpointResolver.chatUrl(baseUrl, protocol)
        val response = withErrorDetail(url) {
            api.chat(
                url = url,
                authorization = authHeader(apiKey),
                body = ChatRequest(
                    model = model,
                    messages = messages,
                    temperature = temperature,
                    maxTokens = maxTokens,
                ),
            )
        }
        response.error?.message?.let { error(it) }
        val content = response.choices.firstOrNull()?.message?.content
            ?: error("模型未返回任何内容")
        return ChatResult(content, response.usage?.totalTokens)
    }

    /* ---------------- Anthropic Messages API ---------------- */

    private suspend fun anthropicChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessageDto>,
        temperature: Double,
        maxTokens: Int,
    ): ChatResult {
        val (system, convo) = toAnthropic(messages)
        val url = EndpointResolver.chatUrl(baseUrl, ProviderProtocol.ANTHROPIC)
        val response = withErrorDetail(url) {
            api.anthropicChat(
                url = url,
                headers = anthropicHeaders(apiKey),
                body = AnthropicRequest(
                    model = model,
                    maxTokens = maxTokens,
                    messages = convo,
                    system = system,
                    temperature = temperature,
                ),
            )
        }
        response.error?.message?.let { error(it) }
        val content = response.text().ifBlank { error("模型未返回任何内容") }
        val totalTokens = response.usage?.let { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) }
        return ChatResult(content, totalTokens)
    }

    /**
     * Converts OpenAI-style messages into (system, conversation) for Anthropic: system turns are
     * lifted out into the top-level `system` param, and content parts are mapped to Anthropic blocks
     * (text → text, `image_url` data-URL → base64 image source).
     */
    private fun toAnthropic(messages: List<ChatMessageDto>): Pair<String?, List<AnthropicMessage>> {
        val system = messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .flatMap { it.content }
            .mapNotNull { it.text }
            .joinToString("\n\n")
            .ifBlank { null }

        val convo = messages
            .filter { !it.role.equals("system", ignoreCase = true) }
            .map { msg ->
                AnthropicMessage(
                    role = if (msg.role.equals("assistant", ignoreCase = true)) "assistant" else "user",
                    content = msg.content.mapNotNull { it.toAnthropic() },
                )
            }
            .filter { it.content.isNotEmpty() }

        return system to convo
    }

    private fun ContentPart.toAnthropic(): AnthropicContent? = when {
        type == "image_url" && imageUrl != null && imageUrl.url.startsWith("data:") -> {
            // data:image/jpeg;base64,XXXX
            val mediaType = imageUrl.url.substringAfter("data:").substringBefore(";")
            val data = imageUrl.url.substringAfter("base64,", "")
            if (mediaType.isBlank() || data.isBlank()) null else AnthropicContent.image(mediaType, data)
        }
        text != null -> AnthropicContent.text(text!!)
        else -> null
    }

    /* ---------------- auth ---------------- */

    private fun authHeader(apiKey: String): String? =
        apiKey.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

    private fun anthropicHeaders(apiKey: String): Map<String, String> = buildMap {
        apiKey.trim().takeIf { it.isNotEmpty() }?.let { put("x-api-key", it) }
        put("anthropic-version", ANTHROPIC_VERSION)
    }

    /* ---------------- error reporting ---------------- */

    /**
     * Runs one API call, replacing Retrofit's opaque `HTTP 404` / `HTTP 500` with something the user
     * can act on. Providers explain the real cause in the error body — model id doesn't exist, Base
     * URL mistyped into a doubled path, key expired, quota gone — and Retrofit drops that body on
     * the floor, leaving a bare status code that says nothing about which of those it was.
     *
     * Applied here rather than as an OkHttp interceptor on purpose: the client is shared, and the
     * desktop-remote path inspects `response.code` itself (401 → re-auth, 403 → kicked), which a
     * throwing interceptor would pre-empt.
     */
    private suspend fun <T> withErrorDetail(url: String, block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw IOException(httpErrorMessage(e, url), e)
    }

    private fun httpErrorMessage(e: HttpException, url: String): String {
        val code = e.code()
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        val detail = extractErrorMessage(body)
        // The endpoint is the single most useful clue for a 404 — a doubled `/v1/v1/` or a stray
        // `/chat/completions` typed into the Base URL is visible at a glance once it's printed.
        val hint = when {
            code == 401 || code == 403 -> "API Key 无效或无权限，请检查密钥"
            code == 404 -> "地址或模型不存在。请求地址：$url —— 请检查供应商的 Base URL 与模型名是否正确"
            code == 413 -> "请求体过大，可尝试在设置中减小截图上传尺寸或缩短对话历史"
            code == 429 -> "请求过于频繁或额度已用尽"
            code in 500..599 -> "供应商服务端错误（$code），通常是对方故障或该模型暂时不可用，可稍后重试或换一个模型"
            else -> null
        }
        return buildString {
            append("HTTP $code")
            hint?.let { append("：").append(it) }
            if (detail.isNotBlank()) append("\n供应商返回：").append(detail.take(MAX_ERROR_DETAIL))
        }
    }

    /**
     * Digs the human-readable reason out of an error body. Covers the shapes providers actually use
     * — `{"error":{"message":…}}`, `{"error":"…"}`, `{"message":…}` — and falls back to the raw text
     * for gateways that answer with plain text or an HTML error page.
     */
    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        val parsed = runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching ""
            when (val err = root["error"]) {
                is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                is JsonPrimitive -> err.contentOrNull.orEmpty()
                else -> root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }.getOrNull().orEmpty()
        if (parsed.isNotBlank()) return parsed
        // Not JSON (HTML error page, proxy text): collapse whitespace so one long line stays readable.
        return body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_ERROR_DETAIL = 300
    }
}
