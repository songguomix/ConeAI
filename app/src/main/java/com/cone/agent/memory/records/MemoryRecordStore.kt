package com.cone.agent.memory.records

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cone.agent.data.repository.MemoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recordsDataStore by preferencesDataStore("cone_memory_v2")

/**
 * Persistence for Memory 2.0 records.
 *
 * Lives in its own DataStore file (`cone_memory_v2`) because DataStore forbids two instances on
 * one file — the legacy `cone_memory` store is read exactly once, to migrate its flat facts into
 * records, and is never written again.
 */
@Singleton
class MemoryRecordStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val legacy: MemoryRepository,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(MemoryRecord.serializer())

    private val KEY_RECORDS = stringPreferencesKey("records")
    private val KEY_ENABLED = booleanPreferencesKey("enabled")
    private val KEY_MIGRATED = booleanPreferencesKey("migrated")
    private val KEY_HABITS_MIGRATED = booleanPreferencesKey("habits_migrated")

    val records: Flow<List<MemoryRecord>> = context.recordsDataStore.data.map { prefs ->
        decode(prefs[KEY_RECORDS])
    }

    val enabled: Flow<Boolean> = context.recordsDataStore.data.map { it[KEY_ENABLED] ?: true }

    suspend fun setEnabled(value: Boolean) {
        context.recordsDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun save(records: List<MemoryRecord>) {
        context.recordsDataStore.edit { it[KEY_RECORDS] = json.encodeToString(serializer, records) }
    }

    /**
     * One-time migration of the legacy flat facts. They were user-approved statements, so they
     * enter as confirmed global user facts — the strongest form the engine has.
     */
    suspend fun ensureMigrated() {
        val prefs = context.recordsDataStore.data.first()
        if (prefs[KEY_MIGRATED] == true) return
        val legacyItems = runCatching { legacy.memories.first() }.getOrDefault(emptyList())
        if (legacyItems.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val migrated = legacyItems.map { item ->
                MemoryRecord(
                    id = "legacy-${item.id}",
                    title = MemoryRules.deriveTitle(item.content),
                    content = item.content,
                    type = "user_fact",
                    layer = "global",
                    importance = 0.7,
                    confidence = 0.9,
                    status = "confirmed",
                    origin = MemoryWire.ORIGIN_MIGRATION,
                    createdAt = item.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = item.updatedAt.takeIf { it > 0 } ?: now,
                )
            }
            val existing = records.first()
            save(existing + migrated.filter { m -> existing.none { it.id == m.id } })
        }
        context.recordsDataStore.edit { it[KEY_MIGRATED] = true }
    }

    /** The workflow habits live in another store and are imported by the code layer. */
    suspend fun habitsMigrated(): Boolean = context.recordsDataStore.data.first()[KEY_HABITS_MIGRATED] ?: false

    suspend fun markHabitsMigrated() {
        context.recordsDataStore.edit { it[KEY_HABITS_MIGRATED] = true }
    }

    private fun decode(raw: String?): List<MemoryRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
