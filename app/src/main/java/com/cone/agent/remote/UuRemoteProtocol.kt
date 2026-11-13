package com.cone.agent.remote

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** UU's public cross-app protocol; it provides no embedded screen/video SDK. */
internal object UuRemoteProtocol {
    const val PACKAGE = "com.netease.uuremote"
    const val CALLBACK_SCHEME = "coneai-uu"
    const val DOWNLOAD_URL = "https://adl.netease.com/d/g/uuremote/c/gw?type=android"
    const val MAX_REQUEST_AGE_MS = 24 * 60 * 60 * 1000L
    private const val BASE = "uuremote://external/device"

    enum class Mode(val value: Int) { FULLSCREEN(0), PIP(1) }
    @Serializable enum class Kind { BIND, CONTROL }
    @Serializable data class Pending(
        val nonce: String, val computerId: String, val kind: Kind, val createdAt: Long,
        val deviceId: String? = null,
    )
    data class Result(val deviceId: String? = null, val error: String? = null, val pipReady: Boolean = false)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun bindUrl(nonce: String): String = "$BASE/authorize?callback=${encode("$CALLBACK_SCHEME://bind")}&nonce=${encode(nonce)}"

    fun controlUrl(deviceId: String, mode: Mode, nonce: String): String {
        require(deviceId.isNotBlank())
        val url = "$BASE/control?anonymous_device_id=${encode(deviceId)}&window_type=${mode.value}"
        // Fullscreen never calls back. Do not create a waiting state or pass a callback for it.
        return if (mode == Mode.FULLSCREEN) url else
            "$url&callback=${encode("$CALLBACK_SCHEME://control?request_id=${encode(nonce)}")}"
    }

    fun supportedVersion(version: String?): Boolean {
        val parts = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(version?.removePrefix("v") ?: "") ?: return false
        val actual = (1..3).map { parts.groupValues[it].toIntOrNull() ?: 0 }
        val minimum = listOf(4, 42, 0)
        actual.zip(minimum).forEach { (a, b) -> if (a != b) return a > b }
        return true
    }

    private fun query(raw: String): Pair<URI, Map<String, String>>? = runCatching {
        if (raw.length > 20_000) return null
        val uri = URI(raw)
        if (uri.scheme != CALLBACK_SCHEME || uri.host !in setOf("bind", "control") ||
            uri.rawUserInfo != null || uri.port != -1 || !uri.rawPath.isNullOrEmpty() || uri.fragment != null) return null
        val result = linkedMapOf<String, String>()
        for (part in (uri.rawQuery ?: "").split('&')) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2) return null
            val key = URLDecoder.decode(pair[0], "UTF-8")
            if (result.containsKey(key)) return null
            result[key] = URLDecoder.decode(pair[1], "UTF-8")
        }
        uri to result
    }.getOrNull()

    fun requestId(raw: String): String? = query(raw)?.let { (uri, params) ->
        params[if (uri.host == "bind") "nonce" else "request_id"]?.takeIf { it.length in 1..128 }
    }

    /** Reject wrong hosts, unsolicited/replayed nonces, mixed success/error and stale requests. */
    fun parseCallback(raw: String, pending: Pending, now: Long): Result? {
        if (now < pending.createdAt || now - pending.createdAt > MAX_REQUEST_AGE_MS) return null
        val (uri, params) = query(raw) ?: return null
        val expectedHost = if (pending.kind == Kind.BIND) "bind" else "control"
        if (uri.host != expectedHost) return null
        val received = params[if (pending.kind == Kind.BIND) "nonce" else "request_id"] ?: return null
        if (!MessageDigest.isEqual(received.toByteArray(), pending.nonce.toByteArray())) return null
        if (pending.kind == Kind.BIND) {
            val id = params["anonymous_device_id"]
            val error = params["error"]
            if ((id != null) == (error != null)) return null
            if (id != null) {
                if (id.isBlank() || id.length > 8192 || id.any { it.code < 32 }) return null
                return Result(deviceId = id)
            }
            if (error.isNullOrBlank()) return null
            return Result(error = error.takeIf { it in bindErrors } ?: "internal_error")
        }
        return when (params["status"]) {
            "success" -> if (params.containsKey("reason")) null else Result(pipReady = true)
            "failure" -> params["reason"]?.takeIf { it.isNotBlank() }?.let {
                Result(error = it.takeIf { value -> value in controlErrors } ?: "connect_failed")
            }
            else -> null
        }
    }

    private val bindErrors = setOf("no_device", "user_cancel", "timeout", "not_logined", "superseded", "logout", "internal_error")
    private val controlErrors = setOf("busy", "device_offline", "device_not_found", "pip_unsupported", "login_timeout",
        "network_unavailable", "not_allowed", "connect_timeout", "connect_failed", "user_cancel", "invalid_request", "internal_error")
}
