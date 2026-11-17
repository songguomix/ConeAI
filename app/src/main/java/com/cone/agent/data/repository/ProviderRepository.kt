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
import com.cone.agent.data.remote.FREE_BASE_URL
import com.cone.agent.data.remote.FREE_PROVIDER_ID
import com.cone.agent.data.remote.FreeRelay
import com.cone.agent.data.remote.LlmClient
import com.cone.agent.domain.model.ModelInfo
import com.cone.agent.domain.model.Provider
import com.cone.agent.domain.model.ProviderProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val freeRelay: FreeRelay,
) {

    val providers: Flow<List<Provider>> = providerDao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    /** The built-in catalog is driven by the relay; Room only contains user-configured providers. */
    val allModels: Flow<List<VisionModelView>> = combine(freeRelay.status, modelDao.observeAllModels()) { status, saved ->
        val free = status?.takeIf { it.available }?.models.orEmpty()
        if (free.isEmpty()) saved else {
            val name = LocaleHelper.string(context, R.string.free_provider_name)
            free.mapIndexed { index, model ->
                VisionModelView(
                    modelRowId = -1L - index,
                    providerId = FREE_PROVIDER_ID,
                    providerName = name,
                    modelId = model.id,
                    displayName = model.name,
                    supportsVision = model.supportsVision,
                    supportsTools = model.supportsTools,
                )
            } + saved
        }
    }

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
        require(id != FREE_PROVIDER_ID && protocol != ProviderProtocol.FREE)
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
        if (providerId == FREE_PROVIDER_ID) return ResolvedProvider(
            id = FREE_PROVIDER_ID,
            name = LocaleHelper.string(context, R.string.free_provider_name),
            baseUrl = FREE_BASE_URL,
            apiKey = "",
            protocol = ProviderProtocol.FREE,
        )
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
        if (providerId == FREE_PROVIDER_ID) {
            return Result.failure(IllegalArgumentException(LocaleHelper.string(context, R.string.provider_missing)))
        }
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
