package com.cone.agent.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** A parsed pairing target: the desktop's base URL, per-session token, and optional password. */
data class RemoteEndpoint(val base: String, val token: String, val password: String? = null) {
    fun eventsUrl() = "$base/events?t=$token"
    fun cmdUrl() = "$base/cmd?t=$token"

    /** A short, token-free label for the UI. */
    val display: String get() = base.removePrefix("http://").removePrefix("https://")

    companion object {
        /**
         * Parse a scanned/pasted pairing URL such as `http://192.168.1.23:8723/?t=<32-hex>` (LAN) or
         * `https://foo.trycloudflare.com/?t=<token>` (tunnel) into [RemoteEndpoint]. Returns null when
         * the input is not a valid http(s) URL carrying a `t` token.
         */
        fun parse(raw: String): RemoteEndpoint? {
            val url = raw.trim().toHttpUrlOrNull() ?: return null
            val token = url.queryParameter("t")?.takeIf { it.isNotBlank() } ?: return null
            // The QR may also carry the password (`&p=`) so scanning auto-connects.
            val password = url.queryParameter("p")?.takeIf { it.isNotBlank() }
            val portPart = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
            return RemoteEndpoint("${url.scheme}://${url.host}$portPart", token, password)
        }
    }
}

sealed interface RemoteStatus {
    data object Connecting : RemoteStatus
    data object Connected : RemoteStatus
    /** The server rejected the credentials (token/password) — the UI should prompt for a password. */
    data object AuthRequired : RemoteStatus
    /** The desktop kicked this device (HTTP 403); stop and leave the mirror. */
    data object Kicked : RemoteStatus
    data class Error(val message: String) : RemoteStatus
}

/** Just the live streaming fields, sent as a tiny `stream` frame between full snapshots. */
data class StreamDelta(
    val isStreaming: Boolean,
    val streamingContent: String?,
    val streamingReasoningContent: String?,
    val streamingStatus: String?,
    val streamingToolName: String?,
)

/** Thrown on an HTTP 401 so the stream loop stops retrying and the UI can ask for a password. */
private class RemoteAuthException : Exception()

/** Thrown on an HTTP 403 (kicked/blocked by the desktop) so the loop stops without retrying. */
private class RemoteKickedException : Exception()

/**
 * Mirrors and drives a ConeCode desktop session. Pulls a live state snapshot over Server-Sent Events
 * (`GET /events`) and posts intent back (`POST /cmd`) — the same HTTP+SSE bridge the bundled web
 * client uses. SSE is read by hand off OkHttp's response stream so no extra dependency is needed.
 */
class RemoteClient(
    baseClient: OkHttpClient,
    val endpoint: RemoteEndpoint,
) {
    // SSE is a long-lived stream kept alive by 25s server pings; disable the read timeout so a quiet
    // (idle) session is not torn down. Reuses the shared connection pool / dispatcher.
    private val streamClient: OkHttpClient = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val commandClient: OkHttpClient = baseClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // In-flight request/reply calls (file browse, read/write, exec, openFolder) keyed by reqId.
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    /**
     * Connect and keep mirroring until the calling coroutine is cancelled, reconnecting with capped
     * backoff whenever the stream drops. [onStatus] reports connection phase; [onSnapshot] fires once
     * per received state frame.
     */
    suspend fun stream(
        onStatus: (RemoteStatus) -> Unit,
        onSnapshot: (RemoteSnapshot) -> Unit,
        onStream: (StreamDelta) -> Unit,
    ) {
        var backoff = 1_000L
        while (coroutineContext.isActive) {
            onStatus(RemoteStatus.Connecting)
            try {
                readStream(onStatus, onSnapshot, onStream)
                backoff = 1_000L // a clean session resets the backoff
            } catch (e: RemoteAuthException) {
                // Wrong/missing password — stop retrying; the UI prompts and reconnects.
                if (coroutineContext.isActive) onStatus(RemoteStatus.AuthRequired)
                return
            } catch (e: RemoteKickedException) {
                // Kicked by the desktop — stop retrying and leave the mirror.
                if (coroutineContext.isActive) onStatus(RemoteStatus.Kicked)
                return
            } catch (e: IOException) {
                if (coroutineContext.isActive) onStatus(RemoteStatus.Error(e.message ?: "connection lost"))
            }
            if (!coroutineContext.isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(8_000L)
        }
    }

    private suspend fun readStream(
        onStatus: (RemoteStatus) -> Unit,
        onSnapshot: (RemoteSnapshot) -> Unit,
        onStream: (StreamDelta) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint.eventsUrl())
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply { endpoint.password?.let { header("x-remote-pass", it) } }
            .build()
        val call: Call = streamClient.newCall(request)
        // Cancelling the coroutine cancels the in-flight call, which unblocks the blocking line read.
        val handle = coroutineContext[Job]?.invokeOnCompletion {
            runCatching { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (response.code == 401) throw RemoteAuthException()
                if (response.code == 403) throw RemoteKickedException()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                onStatus(RemoteStatus.Connected)
                val source = response.body?.source() ?: throw IOException("empty body")
                val data = StringBuilder()
                while (coroutineContext.isActive) {
                    val line = source.readUtf8Line() ?: break // stream closed by peer
                    when {
                        line.isEmpty() -> {
                            // Blank line terminates an event; dispatch the accumulated data payload.
                            if (data.isNotEmpty()) {
                                dispatch(data.toString(), onSnapshot, onStream)
                                data.setLength(0)
                            }
                        }
                        line.startsWith("data:") -> {
                            val chunk = line.substring(5).removePrefix(" ")
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(chunk)
                        }
                        // `:` comments (pings), `retry:` and `event:` lines are ignored.
                    }
                }
            }
        } finally {
            handle?.dispose()
        }
    }

    private fun dispatch(
        payload: String,
        onSnapshot: (RemoteSnapshot) -> Unit,
        onStream: (StreamDelta) -> Unit,
    ) {
        runCatching {
            val obj = RemoteJson.parseToJsonElement(payload).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "state" -> {
                    val frame = RemoteJson.decodeFromString(RemoteFrame.serializer(), payload)
                    frame.snapshot?.let(onSnapshot)
                }
                // Tiny token-growth delta between full snapshots.
                "stream" -> onStream(
                    StreamDelta(
                        isStreaming = obj["isStreaming"]?.jsonPrimitive?.booleanOrNull ?: true,
                        streamingContent = obj["streamingContent"]?.jsonPrimitive?.contentOrNull,
                        streamingReasoningContent = obj["streamingReasoningContent"]?.jsonPrimitive?.contentOrNull,
                        streamingStatus = obj["streamingStatus"]?.jsonPrimitive?.contentOrNull,
                        streamingToolName = obj["streamingToolName"]?.jsonPrimitive?.contentOrNull,
                    ),
                )
                // A reply to a pending request (file/exec/openFolder); hand the whole object back.
                "reply" -> obj["reqId"]?.jsonPrimitive?.contentOrNull?.let { id ->
                    pending.remove(id)?.complete(obj)
                }
            }
        }
    }

    /** Post a command back to the desktop. Failures are swallowed (the SSE state is authoritative). */
    suspend fun send(command: RemoteCommand) {
        postJson(command.toJsonObject())
    }

    /**
     * Send a request and await its `reply` frame (matched by a generated reqId), with a timeout.
     * Returns the reply object, or null on timeout. Used by the file browser / editor / terminal.
     */
    suspend fun request(type: String, fields: Map<String, String>): JsonObject? {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[reqId] = deferred
        val obj = buildJsonObject {
            put("type", JsonPrimitive(type))
            put("reqId", JsonPrimitive(reqId))
            fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return try {
            postJson(obj)
            withTimeoutOrNull(20_000) { deferred.await() }
        } finally {
            pending.remove(reqId)
        }
    }

    private suspend fun postJson(obj: JsonObject) = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val json = RemoteJson.encodeToString(JsonObject.serializer(), obj)
        val request = Request.Builder()
            .url(endpoint.cmdUrl())
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { endpoint.password?.let { header("x-remote-pass", it) } }
            .build()
        runCatching { commandClient.newCall(request).execute().use { } }
        Unit
    }
}
