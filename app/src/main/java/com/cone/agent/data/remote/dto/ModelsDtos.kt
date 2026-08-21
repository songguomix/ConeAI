package com.cone.agent.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelsResponse(
    val data: List<ModelDto> = emptyList(),
    val models: List<ModelDto> = emptyList(),
) {
    /** Some endpoints (e.g. Ollama) use `models` instead of `data`. */
    fun all(): List<ModelDto> = if (data.isNotEmpty()) data else models
}

@Serializable
data class ModelDto(
    val id: String? = null,
    val name: String? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    // Capability metadata — only some providers populate these (OpenRouter, Ollama, gateways…).
    val architecture: Architecture? = null,
    val capabilities: List<String>? = null,
    val modalities: List<String>? = null,
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
    @SerialName("supported_parameters") val supportedParameters: List<String>? = null,
) {
    fun resolveId(): String = id ?: name ?: ""

    /**
     * Whether the endpoint advertises tool / function-calling support (used to gate 联网搜索).
     * Read only from API-declared fields — never guessed from the model name. When the API gives no
     * capability info, returns false (treated as unsupported).
     */
    fun apiAdvertisesTools(): Boolean {
        val signals = buildList {
            supportedParameters?.let { addAll(it) }
            capabilities?.let { addAll(it) }
        }.joinToString(" ").lowercase()
        return signals.contains("tool") || signals.contains("function")
    }

    /**
     * Whether the endpoint *itself* advertises vision/image input.
     * - `true`  : the API declares image/vision input support
     * - `false` : the API declares modalities but none include image
     * - `null`  : the API gives no capability info → caller should fall back to a heuristic
     */
    fun apiAdvertisesVision(): Boolean? {
        val signals = mutableListOf<String>()
        var hasInfo = false

        architecture?.inputModalities?.let { hasInfo = true; signals += it }
        inputModalities?.let { hasInfo = true; signals += it }
        capabilities?.let { hasInfo = true; signals += it }
        modalities?.let { hasInfo = true; signals += it }
        architecture?.modality?.let { modality ->
            hasInfo = true
            // e.g. "text+image->text": only the input side (before "->") counts for vision input.
            signals += if (modality.contains("->")) modality.substringBefore("->") else modality
        }

        if (!hasInfo) return null
        val joined = signals.joinToString(" ").lowercase()
        return joined.contains("image") || joined.contains("vision") || joined.contains("multimodal")
    }
}

@Serializable
data class Architecture(
    val modality: String? = null,
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
    @SerialName("output_modalities") val outputModalities: List<String>? = null,
)
