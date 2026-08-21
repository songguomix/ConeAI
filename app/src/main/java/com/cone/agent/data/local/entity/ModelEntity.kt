package com.cone.agent.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["providerId", "modelId"], unique = true), Index("providerId")],
)
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val modelId: String,
    val displayName: String,
    val supportsVision: Boolean,
    /** Tool / function-calling support, per the API. Gates 联网搜索. */
    val supportsTools: Boolean = false,
)

/** Join projection of a model together with its provider name, for pickers. */
data class VisionModelView(
    val modelRowId: Long,
    val providerId: Long,
    val providerName: String,
    val modelId: String,
    val displayName: String,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
)
