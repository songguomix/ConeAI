package com.cone.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cone.agent.data.local.entity.ModelEntity
import com.cone.agent.data.local.entity.VisionModelView
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY displayName")
    fun observeByProvider(providerId: Long): Flow<List<ModelEntity>>

    @Query(
        """
        SELECT m.id AS modelRowId, m.providerId AS providerId, p.name AS providerName,
               m.modelId AS modelId, m.displayName AS displayName,
               m.supportsVision AS supportsVision, m.supportsTools AS supportsTools
        FROM models m INNER JOIN providers p ON p.id = m.providerId
        ORDER BY p.name, m.displayName
        """
    )
    fun observeAllModels(): Flow<List<VisionModelView>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ModelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelEntity): Long

    @Query("SELECT COUNT(*) FROM models WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun countByProviderAndModel(providerId: Long, modelId: String): Int

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM models WHERE providerId = :providerId")
    suspend fun deleteByProvider(providerId: Long)
}
