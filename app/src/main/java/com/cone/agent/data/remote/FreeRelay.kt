package com.cone.agent.data.remote

import android.content.Context
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.remote.dto.ChatRequest
import com.cone.agent.data.remote.dto.ChatResponse
import com.cone.agent.data.remote.dto.ChatStreamChunk
import com.cone.agent.data.remote.dto.Usage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSource
import java.io.EOFException
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

const val FREE_PROVIDER_ID = -1L
const val FREE_BASE_URL = "https://conellm.cc.cd"
internal const val FREE_CHAT_URL = "$FREE_BASE_URL/v1/chat/completions"
internal const val FREE_STATUS_URL = "$FREE_BASE_URL/status"
private const val DEFAULT_MAX_REQUEST_BYTES = 32L * 1024 * 1024
private const val DEFAULT_STREAM_IDLE_TIMEOUT_MS = 120_000L
private const val MAX_SSE_LINE_BYTES = 1024L * 1024

enum class FreeMode { OFF, ON, SCHEDULED }

data class FreeModel(
    val id: String,
    val name: String,
    val supportsVision: Boolean,
    val supportsTools: Boolean,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
)

data class FreeStatus(
    val available: Boolean,
    val mode: FreeMode,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val models: List<FreeModel>,
    val maxRequestBytes: Long,
    val streamIdleTimeoutMs: Long,
    val resetsAt: Instant?,
    val usageResetAt: Instant?,
    val reportedUsed: Long?,
    val reportedLimit: Long?,
)

class FreeRelayException internal constructor(
    message: String,
    val canRetryNonStreaming: Boolean,
) : IOException(message)

/** The built-in relay uses fixed HTTPS URLs and never receives provider settings or credentials. */
@Singleton
class FreeRelay @Inject internal constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
    private val json: Json,
    private val usage: FreeUsage,
) {
    private val relayHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val statusHttp = relayHttp.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val requestJson = Json(json) { encodeDefaults = true }
    private val refreshLock = Mutex()
    private var lastRefreshNanos: Long? = null

    private val currentStatus = MutableStateFlow<FreeStatus?>(null)
    val status: StateFlow<FreeStatus?> = currentStatus.asStateFlow()
    private val currentLoading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = currentLoading.asStateFlow()
    val weeklyUsed: Flow<Long> = usage.weeklyUsed

    suspend fun refresh(): FreeStatus? = refreshWithMaxAge(1_000_000_000L)

    /** Calls can originate outside MainActivity, such as from the system voice assistant. */
    internal suspend fun statusForRequest(): FreeStatus? = refreshWithMaxAge(30_000_000_000L)

    private suspend fun refreshWithMaxAge(maxAgeNanos: Long): FreeStatus? = refreshLock.withLock {
        val lastRefresh = lastRefreshNanos
        if (lastRefresh != null && System.nanoTime() - lastRefresh < maxAgeNanos) {
            return@withLock currentStatus.value
        }
        currentLoading.value = true
        try {
            val next = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(FREE_STATUS_URL)
                    .header("Cache-Control", "no-store")
                    .build()
                try {
                    readResponse(statusHttp.newCall(request)) { body ->
                        parseFreeStatus(json, body.readBounded(1024L * 1024))
                    }
                } catch (_: FreeRelayException) {
                    null
                }
            }
            // Keep the quota and catalogue in sync before allowing new completions.
            updateUsage(resetAt = next?.usageResetAt)
            currentStatus.value = next
            lastRefreshNanos = System.nanoTime()
            next
        } finally {
            currentLoading.value = false
        }
    }

    internal suspend fun chat(body: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        val response = readResponse(completionCall(body)) { rb ->
            val value = json.readFreeEnvelope(rb.readBounded(16L * 1024 * 1024))
            json.decodeFromJsonElement<ChatResponse>(value)
        }
        val tokens = response.usage.reportedTotal()
        updateUsage(tokens = tokens ?: 0)
        val content = response.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            ?: throw failure(FreeFailure.UNAVAILABLE)
        ChatResult(content, tokens)
    }

    internal fun chatStream(body: ChatRequest): Flow<ChatChunk> = flow {
        var tokens: Int? = null
        var hasContent = false
        val leadingWhitespace = StringBuilder()
        readResponse(completionCall(body)) { rb ->
            consumeFreeStream(json, rb.source()) { chunk ->
                chunk.usage.reportedTotal()?.let { tokens = it }
                val text = chunk.choices.firstOrNull()?.delta?.content.orEmpty()
                if (text.isNotEmpty()) {
                    when {
                        hasContent -> emit(ChatChunk.Delta(text))
                        text.isNotBlank() -> {
                            hasContent = true
                            emit(ChatChunk.Delta(leadingWhitespace.append(text).toString()))
                            leadingWhitespace.clear()
                        }
                        leadingWhitespace.length + text.length <= MAX_SSE_LINE_BYTES -> leadingWhitespace.append(text)
                    }
                }
            }
        }
        updateUsage(tokens = tokens ?: 0)
        if (!hasContent) throw failure(FreeFailure.UNAVAILABLE)
        emit(ChatChunk.Done(tokens))
    }.flowOn(Dispatchers.IO)

    private suspend fun completionCall(chat: ChatRequest): Call {
        val active = statusForRequest()?.takeIf { it.available }
            ?: throw failure(FreeFailure.UNAVAILABLE, canRetry = false)
        val model = active.models.firstOrNull { it.id == chat.model }
            ?: throw failure(FreeFailure.UNAVAILABLE, canRetry = false)
        val used = try {
            usage.current()
        } catch (_: IOException) {
            throw failure(FreeFailure.UNAVAILABLE, canRetry = false)
        }
        if (used >= FREE_WEEKLY_TOKEN_LIMIT) throw failure(FreeFailure.QUOTA, canRetry = false)

        val maxTokens = chat.maxTokens?.let { requested ->
            minOf(requested, model.maxOutputTokens ?: requested)
        }
        val body = requestJson.encodeToString(ChatRequest.serializer(), chat.copy(maxTokens = maxTokens))
            .toRequestBody("application/json".toMediaType())
        if (body.contentLength() > active.maxRequestBytes) {
            throw failure(FreeFailure.REQUEST_SIZE, canRetry = false)
        }
        val request = Request.Builder().url(FREE_CHAT_URL).post(body).build()
        return relayHttp.newBuilder()
            .readTimeout(active.streamIdleTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
    }

    private suspend fun <T> readResponse(call: Call, read: suspend (ResponseBody) -> T): T = coroutineScope {
        // Cancel the socket when a collecting screen stops, even during a blocking read.
        val finished = AtomicBoolean(false)
        val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                val body = response.body ?: throw failure(FreeFailure.UNAVAILABLE)
                if (!response.isSuccessful) {
                    val error = try {
                        body.source().timeout().deadline(5, TimeUnit.SECONDS)
                        json.parseToJsonElement(body.readBounded(65_536)) as? JsonObject
                    } catch (_: IOException) {
                        null
                    } catch (_: SerializationException) {
                        null
                    }
                    val reason = freeFailure(error, response.code)
                    throw failure(
                        reason,
                        canRetry = reason == FreeFailure.UNAVAILABLE && response.code >= 500,
                        httpStatus = response.code,
                    )
                }
                read(body)
            }
        } catch (e: InvalidFreeResponse) {
            throw failure(
                e.failure,
                canRetry = e.failure == FreeFailure.UNAVAILABLE && (e.httpStatus == null || e.httpStatus >= 500),
                httpStatus = e.httpStatus,
            )
        } catch (e: SerializationException) {
            throw failure(FreeFailure.UNAVAILABLE)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            if (e is FreeRelayException) throw e
            throw failure(FreeFailure.UNAVAILABLE)
        } finally {
            finished.set(true)
            cancelCall.cancel()
        }
    }

    private suspend fun updateUsage(resetAt: Instant? = null, tokens: Int = 0) {
        try {
            usage.update(resetAt, tokens)
        } catch (_: IOException) {
            // A storage failure must not discard an otherwise successful reply.
        }
    }

    private fun failure(failure: FreeFailure, canRetry: Boolean = true, httpStatus: Int? = null): FreeRelayException {
        val messageRes = when (failure) {
            FreeFailure.UNAVAILABLE -> R.string.free_provider_unavailable
            FreeFailure.REQUEST_SIZE -> R.string.free_provider_request_too_large
            FreeFailure.RATE_LIMIT -> R.string.free_provider_rate_limited
            FreeFailure.TOOLS -> R.string.free_provider_tools_unsupported
            FreeFailure.QUOTA -> R.string.free_provider_usage_exhausted
        }
        val message = LocaleHelper.string(context, messageRes)
        val detail = httpStatus?.let { " (HTTP $it)" }.orEmpty()
        return FreeRelayException(message + detail, canRetry)
    }
}

internal fun parseFreeStatus(json: Json, body: String): FreeStatus {
    val data = json.parseToJsonElement(body) as? JsonObject ?: throw SerializationException("Invalid status")
    val available = data.boolean("available")
    val usage = data["usage"] as? JsonObject ?: data
    val limits = data["limits"] as? JsonObject
    fun usageNumber(vararg keys: String) = keys.firstNotNullOfOrNull(usage::number)
        ?: keys.firstNotNullOfOrNull(data::number)
    fun usageDate(vararg keys: String) = keys.firstNotNullOfOrNull { parseFreeInstant(usage.string(it)) }
        ?: keys.firstNotNullOfOrNull { parseFreeInstant(data.string(it)) }

    return FreeStatus(
        available = available,
        mode = when (data.string("mode")) {
            "scheduled" -> FreeMode.SCHEDULED
            "on" -> FreeMode.ON
            else -> FreeMode.OFF
        },
        startsAt = parseFreeInstant(data.string("startsAt")),
        endsAt = parseFreeInstant(data.string("endsAt")),
        models = if (available) (data["models"] as? JsonArray).orEmpty().take(100).mapNotNull { value ->
            val model = value as? JsonObject ?: return@mapNotNull null
            val id = model.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = model.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FreeModel(
                id = id,
                name = name,
                supportsVision = model.boolean("supportsVision"),
                supportsTools = model.boolean("supportsTools"),
                contextWindow = model.number("context_window")?.positiveInt(),
                maxOutputTokens = model.number("max_output_tokens")?.positiveInt(),
            )
        }.distinctBy { it.id } else emptyList(),
        maxRequestBytes = limits?.number("maxRequestBytes")?.takeIf { it > 0 } ?: DEFAULT_MAX_REQUEST_BYTES,
        streamIdleTimeoutMs = limits?.number("streamIdleTimeoutMs")?.positiveInt()?.toLong()
            ?: DEFAULT_STREAM_IDLE_TIMEOUT_MS,
        resetsAt = usageDate("resetsAt", "resetAt", "weeklyResetAt"),
        usageResetAt = parseFreeInstant(data.string("usageResetAt")) ?: usageDate("usageResetAt"),
        reportedUsed = usageNumber("used", "usedTokens", "tokensUsed", "weeklyUsed", "usageTokens"),
        reportedLimit = usageNumber("limit", "weeklyLimit", "tokenLimit", "cap")?.takeIf { it > 0 },
    )
}

internal fun parseFreeInstant(value: String?): Instant? = try {
    value?.let(Instant::parse)
} catch (_: DateTimeParseException) {
    null
}

internal suspend fun consumeFreeStream(json: Json, source: BufferedSource, emit: suspend (ChatStreamChunk) -> Unit) {
    var finished = false
    while (true) {
        val newLine = source.indexOf('\n'.code.toByte(), 0, MAX_SSE_LINE_BYTES + 1)
        val line = when {
            newLine >= 0 -> source.readUtf8Line() ?: break
            source.buffer.size > MAX_SSE_LINE_BYTES -> throw InvalidFreeResponse()
            source.buffer.size > 0 -> source.readUtf8()
            else -> break
        }
        val data = line.trimStart().takeIf { it.startsWith("data:") }?.substring(5)?.trim().orEmpty()
        if (data == "[DONE]") return
        if (data.isBlank()) continue
        val chunk = json.decodeFromJsonElement<ChatStreamChunk>(json.readFreeEnvelope(data))
        if (chunk.choices.any { !it.finishReason.isNullOrBlank() }) finished = true
        emit(chunk)
    }
    if (!finished) throw InvalidFreeResponse()
}

internal enum class FreeFailure { UNAVAILABLE, REQUEST_SIZE, RATE_LIMIT, TOOLS, QUOTA }
internal class InvalidFreeResponse(
    val failure: FreeFailure = FreeFailure.UNAVAILABLE,
    val httpStatus: Int? = null,
) : IOException()

private fun Json.readFreeEnvelope(text: String): JsonObject {
    val data = parseToJsonElement(text) as? JsonObject ?: throw InvalidFreeResponse()
    val error = data["error"]
    if (error != null && error != JsonNull) {
        val status = (error as? JsonObject)?.number("status")?.takeIf { it in 400L..599L }?.toInt()
        throw InvalidFreeResponse(freeFailure(data, status), httpStatus = status)
    }
    if (data["choices"] !is JsonArray) throw InvalidFreeResponse()
    return data
}

private fun freeFailure(data: JsonObject?, status: Int?): FreeFailure =
    when ((data?.get("error") as? JsonObject)?.string("code")) {
        "free_tools_unsupported" -> FreeFailure.TOOLS
        "free_request_too_large" -> FreeFailure.REQUEST_SIZE
        "free_rate_limited" -> FreeFailure.RATE_LIMIT
        else -> when (status) {
            413 -> FreeFailure.REQUEST_SIZE
            429 -> FreeFailure.RATE_LIMIT
            else -> FreeFailure.UNAVAILABLE
        }
    }

private fun ResponseBody.readBounded(bytes: Long): String {
    val source = source()
    if (source.request(bytes + 1)) throw EOFException("Free relay response exceeds limit")
    return source.readUtf8()
}

private fun Usage?.reportedTotal(): Int? {
    if (this == null) return null
    totalTokens?.takeIf { it >= 0 }?.let { return it }
    val input = promptTokens?.takeIf { it >= 0 }
    val output = completionTokens?.takeIf { it >= 0 }
    if (input == null && output == null) return null
    return ((input ?: 0).toLong() + (output ?: 0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)
    ?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)
    ?.takeUnless { it.isString }?.booleanOrNull == true

private fun Long.positiveInt(): Int? = takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
