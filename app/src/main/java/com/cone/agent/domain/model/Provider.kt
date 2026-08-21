package com.cone.agent.domain.model

/** A model provider endpoint (OpenAI-compatible). API keys are never held here in plaintext. */
data class Provider(
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI,
    val hasApiKey: Boolean = false,
    /** true → models are typed in by hand; false → fetched via `GET /models` ("自动发现"). */
    val manualModels: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Returns the normalised base url without a trailing slash. */
    val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')
}
