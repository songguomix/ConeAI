package com.cone.agent.domain.model

/** A concrete model exposed by a [Provider]. Only vision models may run as the agent brain. */
data class ModelInfo(
    val id: Long = 0,
    val providerId: Long,
    val modelId: String,
    val displayName: String = modelId,
    val supportsVision: Boolean = false,
    /** Tool / function-calling support, per the API. Gates 联网搜索. */
    val supportsTools: Boolean = false,
)
