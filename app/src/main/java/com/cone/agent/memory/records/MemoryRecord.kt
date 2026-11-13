package com.cone.agent.memory.records

import kotlinx.serialization.Serializable

/**
 * Memory 2.0 record model, ported from ConeCode.
 *
 * A record is a *value node*: one durable fact with its type, its trust level and its
 * provenance, rather than a bare string. Types and layers use the same wire names as
 * ConeCode so a future import/export stays lossless.
 */
object MemoryWire {

    val TYPES = listOf(
        "user_fact", "project_fact", "architecture", "technical_decision", "decision_reason",
        "constraint", "task_result", "progress", "todo", "success_experience",
        "failure_experience", "known_bug", "reusable_knowledge",
    )

    val LAYERS = listOf("global", "project", "episodic", "semantic", "working")

    val STATUSES = listOf("confirmed", "inferred", "pending", "superseded", "archived")

    const val ORIGIN_AGENT = "agent"
    const val ORIGIN_USER = "user"
    const val ORIGIN_MIGRATION = "migration"
}

@Serializable
data class MemoryVersion(
    val version: Int,
    val title: String,
    val content: String,
    val updatedAt: Long,
    val status: String,
    val why: String? = null,
    val importance: Double = 0.5,
    val confidence: Double = 0.65,
)

@Serializable
data class MemoryRecord(
    val id: String,
    val title: String,
    val content: String,
    val type: String = "project_fact",
    val layer: String = "project",
    val projectId: String? = null,
    val tags: List<String> = emptyList(),
    val importance: Double = 0.5,
    val confidence: Double = 0.65,
    val status: String = "inferred",
    val origin: String = "agent",
    val why: String? = null,
    val sourceSessionId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
    val useCount: Int = 0,
    val deletedAt: Long? = null,
    val history: List<MemoryVersion> = emptyList(),
) {
    val isActive: Boolean
        get() = deletedAt == null && status != "archived" && status != "superseded"

    val isGlobal: Boolean get() = layer == "global"
}
