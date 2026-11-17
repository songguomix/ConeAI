package com.cone.agent.remote

import com.cone.agent.data.crypto.KeystoreManager
import com.cone.agent.data.crypto.Sealed
import com.cone.agent.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Metadata the picker can display without opening a saved login. */
data class RemoteComputer(
    val id: String,
    val name: String,
    val endpointLabel: String,
    val canConnect: Boolean,
)

@Serializable
internal data class SavedRemoteComputer(
    val id: String,
    val origin: String,
    val nickname: String? = null,
    val cipherText: String = "",
    val iv: String = "",
) {
    val hasLogin: Boolean get() = cipherText.isNotBlank() && iv.isNotBlank()

    fun toPickerRow(): RemoteComputer {
        val label = origin.removePrefix("https://").removePrefix("http://")
        return RemoteComputer(id, nickname ?: label, label, hasLogin)
    }
}

@Serializable
internal data class SavedRemoteComputers(
    val computers: List<SavedRemoteComputer> = emptyList(),
    val lastComputerId: String? = null,
) {
    fun hasOriginConflict(id: String, origin: String): Boolean =
        computers.any { it.origin == origin && it.id != id }
}

/** Stores endpoint labels separately from Keystore-encrypted pairing URLs and passwords. */
@Singleton
class RemoteComputerDirectory @Inject internal constructor(
    private val settings: SettingsRepository,
    private val keystore: KeystoreManager,
) {
    private val updates = Mutex()
    internal val states = settings.remoteDirectory.map(::decode)

    internal suspend fun initialize() = settings.migrateRemoteDirectory { url, cipher, iv ->
        val sealed = Sealed(cipher.orEmpty(), iv.orEmpty())
        val legacyLogin = open(sealed)
        val verifiedOrigin = legacyLogin?.let { RemoteEndpoint.parse(it.url)?.base }
        val legacyOrigin = url?.safeOrigin()
        val computers = buildList {
            if (verifiedOrigin != null) {
                add(SavedRemoteComputer(newComputerId(), verifiedOrigin, cipherText = sealed.cipherText, iv = sealed.iv))
            }
            if (legacyOrigin != null && legacyOrigin != verifiedOrigin) {
                // Older builds may have saved the full URL in this preference. It now lives only in ciphertext.
                val oldLogin = url?.takeIf { RemoteEndpoint.parse(it) != null }?.let { seal(SavedLogin(it)) }
                add(SavedRemoteComputer(newComputerId(), legacyOrigin, cipherText = oldLogin?.cipherText.orEmpty(), iv = oldLogin?.iv.orEmpty()))
            }
        }
        encode(SavedRemoteComputers(computers, computers.firstOrNull { it.hasLogin }?.id))
    }

    internal suspend fun load(): SavedRemoteComputers {
        initialize()
        return states.first()
    }

    internal suspend fun login(profile: SavedRemoteComputer): SavedLogin? = withContext(Dispatchers.IO) {
        open(Sealed(profile.cipherText, profile.iv))
    }

    internal suspend fun remember(
        id: String,
        origin: String,
        nickname: String?,
        login: SavedLogin,
        replaceExisting: Boolean,
    ): String? = updates.withLock {
        initialize()
        val sealed = withContext(Dispatchers.IO) { seal(login) }
        if (sealed.cipherText.isBlank() || sealed.iv.isBlank()) return@withLock id
        var savedId: String? = id
        settings.updateRemoteDirectory { raw ->
            val current = decode(raw)
            if (replaceExisting && current.hasOriginConflict(id, origin)) {
                savedId = null
                return@updateRemoteDirectory raw ?: encode(current)
            }
            val sameOrigin = current.computers.firstOrNull { it.origin == origin }
            val existing = current.computers.firstOrNull { it.id == id }
            val retained = if (replaceExisting) existing else sameOrigin ?: existing
            val saved = SavedRemoteComputer(
                id = retained?.id ?: id,
                origin = origin,
                nickname = nickname.cleanNickname() ?: retained?.nickname,
                cipherText = sealed.cipherText,
                iv = sealed.iv,
            )
            savedId = saved.id
            encode(SavedRemoteComputers(
                computers = listOf(saved) + current.computers.filter { it.id != saved.id && it.origin != origin },
                lastComputerId = saved.id,
            ))
        }
        savedId
    }

    internal suspend fun rename(id: String, name: String) = updates.withLock {
        initialize()
        settings.updateRemoteDirectory { raw ->
            val current = decode(raw)
            encode(current.copy(computers = current.computers.map {
                if (it.id == id) it.copy(nickname = name.cleanNickname()) else it
            }))
        }
    }

    internal suspend fun remove(id: String) = updates.withLock {
        initialize()
        settings.updateRemoteDirectory { raw ->
            val current = decode(raw)
            encode(SavedRemoteComputers(
                computers = current.computers.filterNot { it.id == id },
                lastComputerId = current.lastComputerId?.takeUnless { it == id },
            ))
        }
    }

    private fun seal(login: SavedLogin): Sealed = keystore.encrypt(RemoteJson.encodeToString(SavedLogin.serializer(), login))

    private fun open(sealed: Sealed): SavedLogin? = keystore.decrypt(sealed).takeIf(String::isNotBlank)?.let { blob ->
        runCatching { RemoteJson.decodeFromString(SavedLogin.serializer(), blob) }.getOrNull()
    }

    private fun encode(computers: SavedRemoteComputers): String =
        RemoteJson.encodeToString(SavedRemoteComputers.serializer(), computers)

    private fun decode(raw: String?): SavedRemoteComputers = raw?.let {
        runCatching { RemoteJson.decodeFromString(SavedRemoteComputers.serializer(), it) }.getOrNull()
    } ?: SavedRemoteComputers()
}

internal fun newComputerId(): String = UUID.randomUUID().toString()

private fun String?.cleanNickname(): String? = this?.trim()?.takeIf(String::isNotBlank)?.take(80)

private fun String.safeOrigin(): String? = trim().toHttpUrlOrNull()?.newBuilder()
    ?.username("")?.password("")?.encodedPath("/")?.query(null)?.fragment(null)
    ?.build()?.toString()?.removeSuffix("/")
