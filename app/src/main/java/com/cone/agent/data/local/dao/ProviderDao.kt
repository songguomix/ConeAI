package com.cone.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cone.agent.data.local.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: Long): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: ProviderEntity): Long

    @Update
    suspend fun update(provider: ProviderEntity)

    @Delete
    suspend fun delete(provider: ProviderEntity)
}
