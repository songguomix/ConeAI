package com.cone.agent.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ----- Anthropic Messages API (https://api.anthropic.com/v1/messages) ----- */

/**
 * NOTE on defaults: the shared [kotlinx.serialization.json.Json] is configured with
 * `encodeDefaults = false`, so a field whose value equals its declared default is dropped from the
 * payload. Anthropic-required fields (`max_tokens`, image `type`, …) therefore intentionally have
 * NO default, guaranteeing they are always serialized.
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessage>,
    // Anthropic takes the system prompt as a top-level parameter, not as a message in the array.
    val system: String? = null,
    val temperature: Double? = null,
    val stream: Boolean? = null,
)

/* ----- Streaming events (SSE): message_start / content_block_delta / message_delta ----- */

@Serializable
data class AnthropicStreamEvent(
    val type: String? = null,
    val delta: AnthropicStreamDelta? = null,
    // message_start carries the message (with input-token usage); message_delta carries output usage.
    val message: AnthropicResponse? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
data class AnthropicStreamDelta(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContent>,
)

@Serializable
data class AnthropicContent(
    val type: String,
    val text: String? = null,
    val source: AnthropicImageSource? = null,
) {
    companion object {
        fun text(value: String) = AnthropicContent(type = "text", text = value)

        fun image(mediaType: String, base64: String) =
            AnthropicContent(type = "image", source = AnthropicImageSource("base64", mediaType, base64))
    }
}

@Serializable
data class AnthropicImageSource(
    val type: String,
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

/* ----- Response ----- */

@Serializable
data class AnthropicResponse(
    val id: String? = null,
    val content: List<AnthropicResponseBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
    val error: AnthropicErrorBody? = null,
) {
    /** Concatenated text of all text blocks (Anthropic may split a reply across multiple blocks). */
    fun text(): String = content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
}

@Serializable
data class AnthropicResponseBlock(
    val type: String,
    val text: String? = null,
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

@Serializable
data class AnthropicErrorBody(
    val type: String? = null,
    val message: String? = null,
)
