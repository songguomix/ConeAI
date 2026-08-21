package com.cone.agent.watch

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A watch that answered on the LAN. */
data class FoundWatch(val host: String, val port: Int, val hello: WatchHello) {
    val baseUrl: String get() = "http://$host:$port"
}

/**
 * Finds the paired watch on the local network and hands it the phone's configuration.
 *
 * Two ways in, run at the same time because either can be the one that works:
 *
 *  * the watch's UDP announcement, which arrives within a second or two when broadcast traffic is
 *    allowed (it is, on a phone's own hotspot);
 *  * a sweep of every /24 the phone has an address on, for networks that drop broadcasts.
 *
 * Its own OkHttp client, not the app-wide one: probing wants second-scale timeouts, and the shared
 * client is tuned for minute-long model streams.
 */
@Singleton
class WatchLinkClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * Looks for the watch named by [payload] until [timeoutMs] runs out.
     *
     * Returns null on timeout rather than throwing: the caller polls this in a loop while the user
     * is still switching the hotspot on, and "not yet" is the normal answer for the first while.
     */
    suspend fun discover(payload: PairPayload, timeoutMs: Long): FoundWatch? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val found = CompletableDeferred<FoundWatch>()

            val listener = launch(Dispatchers.IO) {
                val host = listenForAnnouncement(payload.id, timeoutMs)
                if (host != null) probe(host, payload)?.let { found.complete(it) }
            }
            // Sweeping repeats rather than running once: the watch usually isn't on the network yet
            // when the user has only just started reading the "turn on your hotspot" step.
            val sweeper = launch(Dispatchers.IO) {
                while (isActive && !found.isCompleted) {
                    sweepInto(payload, found)
                    if (!found.isCompleted) delay(1_000)
                }
            }

            val watch = found.await()
            listener.cancel()
            sweeper.cancel()
            watch
        }
    }

    /** Confirms a host really is this watch (id must match) before anything is sent to it. */
    suspend fun probe(host: String, payload: PairPayload): FoundWatch? = withContext(Dispatchers.IO) {
        if (!tcpReachable(host, payload.port, PROBE_CONNECT_MS)) null else hello(host, payload)
    }

    /** The `/cone/hello` half of [probe], without the TCP pre-check the sweep has already done. */
    private suspend fun hello(host: String, payload: PairPayload): FoundWatch? = withContext(Dispatchers.IO) {
        val body = runCatching {
            val request = Request.Builder()
                .url("http://$host:${payload.port}${LinkConstants.PATH_HELLO}")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return@withContext null

        val hello = runCatching { json.decodeFromString(WatchHello.serializer(), body) }.getOrNull()
            ?: return@withContext null
        if (hello.id != payload.id) return@withContext null
        FoundWatch(host, payload.port, hello)
    }

    /** Encrypts the configuration with the pairing token and posts it to the watch. */
    suspend fun push(target: FoundWatch, payload: PairPayload, config: WatchConfigPush): Result<PushResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val plain = json.encodeToString(WatchConfigPush.serializer(), config)
                val envelope = LinkCrypto.seal(payload.token, plain)
                val request = Request.Builder()
                    .url("${target.baseUrl}${LinkConstants.PATH_CONFIG}")
                    .addHeader(LinkConstants.HEADER_TOKEN, payload.token)
                    .post(
                        json.encodeToString(SealedEnvelope.serializer(), envelope)
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()
                val body = http.newCall(request).execute().use { it.body?.string() }
                    ?: error("empty response")
                json.decodeFromString(PushResult.serializer(), body)
            }
        }

    // ------------------------------------------------------------------ discovery mechanics

    private suspend fun listenForAnnouncement(id: String, timeoutMs: Long): String? =
        withContext(Dispatchers.IO) {
            // Some ROMs drop broadcast/multicast frames to sleeping apps without this lock held.
            val lock = runCatching {
                (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("cone-watch-discovery")
                    ?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.bind(InetSocketAddress(LinkConstants.ANNOUNCE_PORT))
                    socket.soTimeout = 1_000
                    val buffer = ByteArray(256)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                        if (!received) continue
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        val parts = text.split("|")
                        if (parts.size >= 2 && parts[0] == LinkConstants.MAGIC && parts[1] == id) {
                            return@use packet.address?.hostAddress
                        }
                    }
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "announcement listener failed", e)
                null
            } finally {
                runCatching { lock?.release() }
            }
        }

    /**
     * One pass over the candidate addresses, completing [found] the moment a watch answers.
     *
     * Deliberately modest parallelism: firing 250 connects at once looks like a port scan to some
     * routers and, on a tethered link, simply drowns out the one address that would have answered.
     */
    private suspend fun sweepInto(payload: PairPayload, found: CompletableDeferred<FoundWatch>) = coroutineScope {
        val gate = Semaphore(SWEEP_PARALLELISM)
        candidateHosts().map { host ->
            launch(Dispatchers.IO) {
                if (found.isCompleted) return@launch
                gate.withPermit {
                    if (found.isCompleted) return@withPermit
                    if (tcpReachable(host, payload.port, SWEEP_CONNECT_MS)) {
                        hello(host, payload)?.let { found.complete(it) }
                    }
                }
            }
        }.joinAll()
    }

    /**
     * Every address in the /24 of each interface the phone holds an IPv4 address on, gateway first.
     * A tethered watch usually lands in the low addresses, so trying those before the long tail
     * finds it in the first batch rather than the last.
     */
    private fun candidateHosts(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { iface -> iface.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.address.size == 4 }
            .mapNotNull { it.hostAddress }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .take(MAX_SUBNETS)
            .flatMap { prefix -> (1..254).map { "$prefix.$it" } }
    }.getOrDefault(emptyList())

    private fun tcpReachable(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    private companion object {
        const val TAG = "WatchLinkClient"
        const val SWEEP_PARALLELISM = 24
        const val SWEEP_CONNECT_MS = 900
        const val PROBE_CONNECT_MS = 1_500
        const val MAX_SUBNETS = 4
    }
}
