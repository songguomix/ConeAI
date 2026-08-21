package com.cone.agent.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ----- Request ----- */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    // Only sent when streaming: asks the provider to include a final usage chunk (token counts).
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

/* ----- Streaming (Server-Sent Events) response chunks ----- */

@Serializable
data class ChatStreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class StreamDelta(val content: String? = null)

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: List<ContentPart>,
)

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null,
) {
    companion object {
        fun text(value: String) = ContentPart(type = "text", text = value)

        /** [dataUrl] should be a `data:image/jpeg;base64,...` URL. */
        fun image(dataUrl: String) = ContentPart(type = "image_url", imageUrl = ImageUrl(dataUrl))
    }
}

@Serializable
data class ImageUrl(val url: String)

/* ----- Response ----- */

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
