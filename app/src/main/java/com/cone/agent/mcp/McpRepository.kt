package com.cone.agent.mcp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import com.cone.agent.data.crypto.KeystoreManager
import com.cone.agent.data.crypto.Sealed

private val Context.mcpDataStore by preferencesDataStore(name = "cone_mcp")

@Singleton
class McpRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val keystore: KeystoreManager,
) {
    private val store = context.mcpDataStore
    private val KEY_SERVERS = stringPreferencesKey("mcp_servers_json")
    private val KEY_CIPHER = stringPreferencesKey("mcp_servers_cipher")
    private val KEY_IV = stringPreferencesKey("mcp_servers_iv")

    val serversFlow: Flow<List<McpServerConfig>> = store.data.map { prefs ->
        val raw = prefs[KEY_CIPHER]?.let { keystore.decrypt(Sealed(it, prefs[KEY_IV].orEmpty())) }
            ?: prefs[KEY_SERVERS] ?: return@map emptyList()
        runCatching { json.decodeFromString(ListSerializer(McpServerConfig.serializer()), raw) }.getOrDefault(emptyList())
    }

    suspend fun getServers(): List<McpServerConfig> = serversFlow.first()

    suspend fun saveServers(list: List<McpServerConfig>) {
        val raw = json.encodeToString(ListSerializer(McpServerConfig.serializer()), list)
        val sealed = keystore.encrypt(raw)
        check(sealed.cipherText.isNotBlank()) { "Unable to securely save MCP configuration" }
        store.edit {
            it[KEY_CIPHER] = sealed.cipherText
            it[KEY_IV] = sealed.iv
            it.remove(KEY_SERVERS)
        }
    }

    suspend fun addServer(config: McpServerConfig): Result<Unit> {
        config.validate().getOrElse { return Result.failure(it) }
        val current = getServers().toMutableList()
        if (current.any { it.id == config.id }) return Result.failure(IllegalArgumentException("duplicate id"))
        if (current.any { it.name == config.name && it.url == config.url }) return Result.failure(IllegalArgumentException("duplicate server"))
        current.add(config)
        saveServers(current)
        return Result.success(Unit)
    }

    suspend fun updateServer(config: McpServerConfig): Result<Unit> {
        config.validate().getOrElse { return Result.failure(it) }
        val current = getServers().toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx < 0) return Result.failure(IllegalArgumentException("not found"))
        current[idx] = config
        saveServers(current)
        return Result.success(Unit)
    }

    suspend fun removeServer(id: String): Result<Unit> {
        val current = getServers().toMutableList()
        val removed = current.removeIf { it.id == id }
        if (!removed) return Result.failure(IllegalArgumentException("not found"))
        saveServers(current)
        return Result.success(Unit)
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
        val current = getServers()
        val cfg = current.firstOrNull { it.id == id } ?: return Result.failure(IllegalArgumentException("not found"))
        return updateServer(cfg.copy(enabled = enabled))
    }

    suspend fun clearAll() {
        store.edit { it.remove(KEY_SERVERS); it.remove(KEY_CIPHER); it.remove(KEY_IV) }
    }
}
