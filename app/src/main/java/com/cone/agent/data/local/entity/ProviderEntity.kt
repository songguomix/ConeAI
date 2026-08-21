package com.cone.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    /** Wire protocol; stored as [com.cone.agent.domain.model.ProviderProtocol.wireName] ("openai"/"anthropic"). */
    val protocol: String = "openai",
    val apiKeyCipher: String = "",
    val apiKeyIv: String = "",
    /**
     * How this provider's model list is populated:
     *  - false (default): "自动发现" — fetched from `GET /models`;
     *  - true: "手动填写" — the user types model names in by hand.
     */
    val manualModels: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
