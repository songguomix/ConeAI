package com.cone.agent.data.remote

import com.cone.agent.domain.model.ProviderProtocol

/**
 * Resolves the concrete request URL from a user-entered Base URL, auto-completing the standard
 * suffix for the selected [ProviderProtocol] — but only when the user hasn't already typed it.
 *
 * Rules (per endpoint, e.g. `/chat/completions`, `/messages`, `/models`):
 *  - base already ends with the endpoint path  → use it as-is (the user wrote the full suffix);
 *  - base's last segment already looks like a version (`/v1`, `/v1beta`, `/v2`, …) → append only
 *    the endpoint path;
 *  - otherwise (bare host)                      → append `/v1` + the endpoint path.
 *
 * This is the single source of truth shared by [LlmClient] (actual calls) and the provider editor
 * UI (the "请求地址" hint), so what the user sees is exactly what gets called.
 */
object EndpointResolver {

    private val VERSION_SEGMENT = Regex("^v\\d+.*", RegexOption.IGNORE_CASE)

    fun chatUrl(baseUrl: String, protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.OPENAI -> resolve(baseUrl, "/chat/completions")
        ProviderProtocol.ANTHROPIC -> resolve(baseUrl, "/messages")
        // CUSTOM: the user owns the exact path — use it verbatim, only trimming a trailing slash.
        ProviderProtocol.CUSTOM -> baseUrl.trim().trimEnd('/')
    }

    fun modelsUrl(baseUrl: String, protocol: ProviderProtocol): String = when (protocol) {
        // For a verbatim chat URL, derive the sibling /models endpoint (e.g. .../chat/completions → .../models).
        ProviderProtocol.CUSTOM -> {
            val base = baseUrl.trim().trimEnd('/')
            when {
                base.isEmpty() -> base
                base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"
                base.endsWith("/models") -> base
                else -> "$base/models"
            }
        }
        else -> resolve(baseUrl, "/models")
    }

    private fun resolve(baseUrl: String, endpoint: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return base
        return when {
            base.endsWith(endpoint) -> base
            VERSION_SEGMENT.matches(base.substringAfterLast('/')) -> "$base$endpoint"
            else -> "$base/v1$endpoint"
        }
    }
}
