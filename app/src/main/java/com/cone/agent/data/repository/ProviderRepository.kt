package com.cone.agent.data.repository

import android.content.Context
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.crypto.KeystoreManager
import com.cone.agent.data.crypto.Sealed
import com.cone.agent.data.local.dao.ModelDao
import com.cone.agent.data.local.dao.ProviderDao
import com.cone.agent.data.local.entity.ModelEntity
import com.cone.agent.data.local.entity.ProviderEntity
import com.cone.agent.data.local.entity.VisionModelView
import com.cone.agent.data.remote.LlmClient
import com.cone.agent.domain.model.ModelInfo
import com.cone.agent.domain.model.Provider
import com.cone.agent.domain.model.ProviderProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Resolved provider configuration including the decrypted API key, used only in memory. */
data class ResolvedProvider(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val protocol: ProviderProtocol,
)

@Singleton
class ProviderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerDao: ProviderDao,
    private val modelDao: ModelDao,
    private val keystore: KeystoreManager,
    private val llmClient: LlmClient,
) {

    val providers: Flow<List<Provider>> = providerDao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    /** All discovered models — the user picks freely for either the agent or 问答 mode. */
    val allModels: Flow<List<VisionModelView>> = modelDao.observeAllModels()

    fun modelsOf(providerId: Long): Flow<List<ModelInfo>> =
        modelDao.observeByProvider(providerId).map { list -> list.map { it.toDomain() } }

    suspend fun upsertProvider(
        id: Long,
        name: String,
        baseUrl: String,
        protocol: ProviderProtocol,
        apiKeyPlain: String?,
        manualModels: Boolean = false,
    ): Long {
        val existing = if (id != 0L) providerDao.getById(id) else null
        val sealed: Sealed = when {
            apiKeyPlain != null -> keystore.encrypt(apiKeyPlain)
            existing != null -> Sealed(existing.apiKeyCipher, existing.apiKeyIv)
            else -> Sealed("", "")
        }
        val entity = ProviderEntity(
            id = id,
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            protocol = protocol.wireName,
            apiKeyCipher = sealed.cipherText,
            apiKeyIv = sealed.iv,
            manualModels = manualModels,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        return providerDao.insert(entity)
    }

    suspend fun deleteProvider(provider: Provider) {
        providerDao.getById(provider.id)?.let { providerDao.delete(it) }
    }

    /** Flips a saved provider between "自动发现" and "手动填写" model modes. */
    suspend fun setManualMode(providerId: Long, manual: Boolean) {
        val existing = providerDao.getById(providerId) ?: return
        if (existing.manualModels != manual) providerDao.update(existing.copy(manualModels = manual))
    }

    /**
     * Manually adds a single model name under a provider. Capabilities are unknown for hand-typed
     * models, so they're recorded as unset — selection isn't gated by them anyway.
     */
    suspend fun addManualModel(providerId: Long, modelId: String): Result<Unit> {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException(LocaleHelper.string(context, R.string.providers_model_blank)))
        }
        if (modelDao.countByProviderAndModel(providerId, trimmed) > 0) {
            return Result.failure(IllegalStateException(LocaleHelper.string(context, R.string.providers_model_exists)))
        }
        modelDao.insert(
            ModelEntity(
                providerId = providerId,
                modelId = trimmed,
                displayName = trimmed,
                supportsVision = false,
                supportsTools = false,
            ),
        )
        return Result.success(Unit)
    }

    /** Removes a single model row (used by the manual list's delete button). */
    suspend fun removeModel(modelRowId: Long) {
        modelDao.deleteById(modelRowId)
    }

    suspend fun resolve(providerId: Long): ResolvedProvider? {
        val entity = providerDao.getById(providerId) ?: return null
        val apiKey = runCatching {
            keystore.decrypt(Sealed(entity.apiKeyCipher, entity.apiKeyIv))
        }.getOrDefault("")
        return ResolvedProvider(
            entity.id,
            entity.name,
            entity.baseUrl,
            apiKey,
            ProviderProtocol.fromWire(entity.protocol),
        )
    }

    /** Calls GET /models and persists results, tagging capabilities strictly from what the API declares. */
    suspend fun discoverModels(providerId: Long): Result<List<ModelInfo>> {
        val resolved = resolve(providerId)
            ?: return Result.failure(IllegalStateException(LocaleHelper.string(context, R.string.provider_missing)))
        return llmClient.listModels(resolved.baseUrl, resolved.apiKey, resolved.protocol).map { discovered ->
            val entities = discovered.map { model ->
                // Capabilities come ONLY from the API — never guessed from the model name. They are
                // recorded for reference but no longer gate selection: the user picks any model freely.
                ModelEntity(
                    providerId = providerId,
                    modelId = model.id,
                    displayName = model.id,
                    supportsVision = model.apiVision == true,
                    supportsTools = model.apiTools,
                )
            }
            modelDao.deleteByProvider(providerId)
            modelDao.upsertAll(entities)
            entities.map { it.toDomain() }
        }
    }

    private fun ProviderEntity.toDomain() = Provider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        protocol = ProviderProtocol.fromWire(protocol),
        hasApiKey = apiKeyCipher.isNotBlank(),
        manualModels = manualModels,
        createdAt = createdAt,
    )

    private fun ModelEntity.toDomain() = ModelInfo(
        id = id,
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        supportsVision = supportsVision,
        supportsTools = supportsTools,
    )
}
