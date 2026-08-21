package com.cone.agent.data.remote

import com.cone.agent.data.remote.dto.AnthropicRequest
import com.cone.agent.data.remote.dto.AnthropicResponse
import com.cone.agent.data.remote.dto.ChatRequest
import com.cone.agent.data.remote.dto.ChatResponse
import com.cone.agent.data.remote.dto.ModelsResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Retrofit interface for OpenAI-compatible and native Anthropic endpoints. Full URLs are passed via
 * [Url] so a single Retrofit instance can talk to arbitrary providers / base urls. The Anthropic
 * variants take a [HeaderMap] because their auth scheme differs (`x-api-key` + `anthropic-version`).
 */
interface LlmApi {
    @POST
    suspend fun chat(
        @Url url: String,
        @Header("Authorization") authorization: String?,
        @Body body: ChatRequest,
    ): ChatResponse

    /** OpenAI-compatible streaming: returns the raw SSE body to parse `data:` chunks incrementally. */
    @Streaming
    @POST
    suspend fun chatStream(
        @Url url: String,
        @Header("Authorization") authorization: String?,
        @Body body: ChatRequest,
    ): ResponseBody

    /** Anthropic streaming: raw SSE body of message_start / content_block_delta / message_delta. */
    @Streaming
    @POST
    suspend fun anthropicChatStream(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: AnthropicRequest,
    ): ResponseBody

    @GET
    suspend fun models(
        @Url url: String,
        @Header("Authorization") authorization: String?,
    ): ModelsResponse

    @POST
    suspend fun anthropicChat(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: AnthropicRequest,
    ): AnthropicResponse

    @GET
    suspend fun anthropicModels(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
    ): ModelsResponse
}
