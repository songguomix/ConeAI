package com.cone.agent.domain.model

/**
 * The wire protocol a provider speaks. The user picks this when adding a provider so the same app
 * can talk to OpenAI-compatible endpoints and native Anthropic (Messages API) endpoints.
 */
enum class ProviderProtocol(val wireName: String) {
    OPENAI("openai"),
    ANTHROPIC("anthropic"),

    /**
     * OpenAI-compatible request/response + Bearer auth, but the Base URL is used VERBATIM as the chat
     * endpoint — no `/v1/...` suffix is auto-completed. For relays / proxies / gateways with a
     * non-standard path the user wants to control exactly.
     */
    CUSTOM("custom");

    companion object {
        /** Maps a stored/raw value back to a protocol, defaulting to [OPENAI] for unknown/blank input. */
        fun fromWire(value: String?): ProviderProtocol =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: OPENAI
    }
}
