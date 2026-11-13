package com.cone.agent.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cone.agent.data.crypto.KeystoreManager
import com.cone.agent.data.crypto.Sealed
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

internal enum class UuAvailability { MISSING, OUTDATED, READY }
internal enum class UuStage { BOUND, BINDING, FULLSCREEN_LAUNCHED, PIP_WAITING, PIP_READY, ERROR }
internal data class UuEvent(val stage: UuStage, val reason: String? = null)
internal data class UuRemoteState(
    val loading: Boolean = true,
    val availability: UuAvailability = UuAvailability.MISSING,
    val devices: Map<String, String> = emptyMap(),
    val events: Map<String, UuEvent> = emptyMap(),
)

@Serializable
private data class UuStored(
    val devices: Map<String, String> = emptyMap(),
    val pending: List<UuRemoteProtocol.Pending> = emptyList(),
)

/** Per-computer bindings and in-flight callbacks survive process death, encrypted at rest. */
@Singleton
internal class UuRemoteConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager,
) {
    private val preferences = context.getSharedPreferences("uu_remote", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var stored = UuStored()
    private val _state = MutableStateFlow(UuRemoteState())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                stored = runCatching {
                    val cipher = preferences.getString("cipher", null) ?: return@runCatching UuStored()
                    val iv = preferences.getString("iv", null) ?: return@runCatching UuStored()
                    RemoteJson.decodeFromString<UuStored>(keystore.decrypt(Sealed(cipher, iv)))
                }.getOrDefault(UuStored())
                val now = System.currentTimeMillis()
                stored = stored.copy(pending = stored.pending.filter { now >= it.createdAt && now - it.createdAt <= UuRemoteProtocol.MAX_REQUEST_AGE_MS })
                val events = stored.pending.associate { it.computerId to UuEvent(if (it.kind == UuRemoteProtocol.Kind.BIND) UuStage.BINDING else UuStage.PIP_WAITING) }
                _state.value = UuRemoteState(false, availability(), stored.devices, events)
                ready.complete(Unit)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun availability(): UuAvailability = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UuRemoteProtocol.bindUrl("availability"))).setPackage(UuRemoteProtocol.PACKAGE)
        if (intent.resolveActivity(context.packageManager) == null) return@runCatching UuAvailability.MISSING
        val version = runCatching { context.packageManager.getPackageInfo(UuRemoteProtocol.PACKAGE, 0).versionName }.getOrNull()
        if (UuRemoteProtocol.supportedVersion(version)) UuAvailability.READY else UuAvailability.OUTDATED
    }.getOrDefault(UuAvailability.MISSING)

    fun refresh() { scope.launch { _state.update { it.copy(availability = availability()) } } }

    private fun event(computerId: String, stage: UuStage, reason: String? = null) {
        _state.update { it.copy(events = it.events + (computerId to UuEvent(stage, reason))) }
    }

    /** Commit before launching UU or acknowledging a callback, so killed processes cannot replay it. */
    private fun commit(next: UuStored): Boolean {
        val sealed = keystore.encrypt(RemoteJson.encodeToString(next))
        if (sealed.cipherText.isBlank() || sealed.iv.isBlank()) return false
        if (!preferences.edit().putString("cipher", sealed.cipherText).putString("iv", sealed.iv).commit()) return false
        stored = next
        _state.update { it.copy(devices = next.devices) }
        return true
    }

    fun bind(computerId: String) {
        scope.launch {
            ready.await()
            mutex.withLock {
                if (!canLaunch(computerId)) return@withLock
                val request = UuRemoteProtocol.Pending(UUID.randomUUID().toString(), computerId,
                    UuRemoteProtocol.Kind.BIND, System.currentTimeMillis())
                val superseded = stored.pending.filter { it.kind == request.kind }.map { it.computerId }.toSet()
                val next = stored.copy(pending = stored.pending.filter { it.kind != request.kind } + request)
                if (!commit(next)) { event(computerId, UuStage.ERROR, "storage_error"); return@withLock }
                _state.update { it.copy(events = it.events - superseded) }
                event(computerId, UuStage.BINDING)
                launch(computerId, UuRemoteProtocol.bindUrl(request.nonce), request.nonce)
            }
        }
    }

    fun control(computerId: String, mode: UuRemoteProtocol.Mode) {
        scope.launch {
            ready.await()
            mutex.withLock {
                if (!canLaunch(computerId)) return@withLock
                val id = stored.devices[computerId] ?: return@withLock
                val request = UuRemoteProtocol.Pending(UUID.randomUUID().toString(), computerId,
                    UuRemoteProtocol.Kind.CONTROL, System.currentTimeMillis(), id)
                val superseded = stored.pending.filter { it.kind == request.kind }.map { it.computerId }.toSet()
                val next = stored.copy(pending = stored.pending.filter { it.kind != request.kind } +
                    if (mode == UuRemoteProtocol.Mode.PIP) listOf(request) else emptyList())
                if (!commit(next)) { event(computerId, UuStage.ERROR, "storage_error"); return@withLock }
                _state.update { it.copy(events = it.events - superseded) }
                event(computerId, if (mode == UuRemoteProtocol.Mode.PIP) UuStage.PIP_WAITING else UuStage.FULLSCREEN_LAUNCHED)
                launch(computerId, UuRemoteProtocol.controlUrl(id, mode, request.nonce), request.nonce)
            }
        }
    }

    private fun canLaunch(computerId: String): Boolean {
        val available = availability()
        _state.update { it.copy(availability = available) }
        if (available == UuAvailability.READY) return true
        event(computerId, UuStage.ERROR, if (available == UuAvailability.MISSING) "not_installed" else "update_required")
        return false
    }

    private suspend fun launch(computerId: String, url: String, nonce: String) {
        try {
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setPackage(UuRemoteProtocol.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            commit(stored.copy(pending = stored.pending.filter { it.nonce != nonce }))
            event(computerId, UuStage.ERROR, "launch_failed")
        }
    }

    fun forget(computerId: String) {
        scope.launch {
            ready.await()
            mutex.withLock {
                if (commit(stored.copy(devices = stored.devices - computerId, pending = stored.pending.filter { it.computerId != computerId }))) {
                    _state.update { it.copy(events = it.events - computerId) }
                } else event(computerId, UuStage.ERROR, "storage_error")
            }
        }
    }

    fun stopWaiting(computerId: String) {
        scope.launch {
            ready.await()
            mutex.withLock {
                if (commit(stored.copy(pending = stored.pending.filter { it.computerId != computerId }))) {
                    _state.update { it.copy(events = it.events - computerId) }
                } else event(computerId, UuStage.ERROR, "storage_error")
            }
        }
    }

    /** Processing belongs to the application, so overlapping result activities cannot cancel it. */
    fun acceptCallback(raw: String, finished: () -> Unit) {
        scope.launch {
            try {
                callback(raw)
            } finally {
                withContext(Dispatchers.Main) { finished() }
            }
        }
    }

    suspend fun callback(raw: String): Boolean = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            val nonce = UuRemoteProtocol.requestId(raw) ?: return@withLock false
            val request = stored.pending.firstOrNull { it.nonce == nonce } ?: return@withLock false
            val result = UuRemoteProtocol.parseCallback(raw, request, System.currentTimeMillis()) ?: return@withLock false
            var devices = stored.devices
            if (result.deviceId != null) devices = devices + (request.computerId to result.deviceId)
            if (result.error == "device_not_found" && devices[request.computerId] == request.deviceId) devices = devices - request.computerId
            val pending = stored.pending.filter { it.nonce != nonce &&
                !(result.deviceId != null && it.computerId == request.computerId && it.kind == UuRemoteProtocol.Kind.CONTROL) }
            if (!commit(UuStored(devices, pending))) { event(request.computerId, UuStage.ERROR, "storage_error"); return@withLock false }
            when {
                result.deviceId != null -> event(request.computerId, UuStage.BOUND)
                result.pipReady -> event(request.computerId, UuStage.PIP_READY)
                else -> event(request.computerId, UuStage.ERROR, result.error)
            }
            true
        }
    }
}
