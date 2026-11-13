package com.cone.agent.update

import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Singleton
class AppUpdateRepository @Inject constructor(client: OkHttpClient) {
    private val client = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): UpdateCheckResult = suspendCancellableCoroutine { continuation ->
        val url = "https://agibase.dev/api/update".toHttpUrl().newBuilder()
            .addQueryParameter("product", "coneai")
            .addQueryParameter("platform", "android")
            .addQueryParameter("version", currentVersion)
            .build()
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw IOException("Update check HTTP ${it.code}")
                        parseUpdateResponse(it.body?.string() ?: throw IOException("Empty update response"), currentVersion)
                    }
                    continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}

data class AppUpdate(
    val version: String,
    val downloadUrl: String,
    val notes: String,
    val fileSize: String,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
    data class Available(val update: AppUpdate) : UpdateCheckResult
}

private val updateJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class UpdateResponse(
    val status: String,
    val product: UpdateProduct? = null,
    val latest: UpdateRelease? = null,
    @SerialName("all_platforms") val allPlatforms: List<UpdateRelease> = emptyList(),
)

@Serializable
private data class UpdateProduct(val slug: String)

@Serializable
private data class UpdateRelease(
    val version: String,
    val platform: String,
    val fileName: String,
    val downloadUrl: String,
    val notes: String = "",
    val changelog: String = "",
    val summary: String = "",
    val fileSizeFormatted: String = "",
)

internal fun parseUpdateResponse(body: String, currentVersion: String): UpdateCheckResult {
    val response = updateJson.decodeFromString<UpdateResponse>(body)
    if (response.status == "no_update") return UpdateCheckResult.NoRelease
    require(response.status == "ok" && response.product?.slug == "coneai") { "Unexpected update product or status" }
    require(response.latest != null || response.allPlatforms.isNotEmpty()) { "Missing update release" }
    // The server falls back to another platform when it has no matching release.
    val release = (listOfNotNull(response.latest) + response.allPlatforms)
        .filter {
            val platform = it.platform.trim().lowercase(java.util.Locale.ROOT)
            val android = when {
                listOf("darwin", "mac", "apple", "osx", "win").any(platform::contains) -> false
                else -> platform.contains("android") || platform.contains("apk")
            }
            android && it.fileName.endsWith(".apk", ignoreCase = true)
        }
        .maxWithOrNull { a, b -> compareAppVersions(a.version, b.version) }
        ?: return UpdateCheckResult.NoRelease
    if (compareAppVersions(release.version, currentVersion) <= 0) return UpdateCheckResult.UpToDate
    val url = release.downloadUrl.toHttpUrlOrNull()
    require(url != null && url.isHttps && url.host == "agibase.dev" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() &&
        url.pathSegments.size == 3 && url.pathSegments.take(2) == listOf("api", "download") &&
        url.pathSegments.last().isNotBlank()) { "Invalid update download URL" }
    return UpdateCheckResult.Available(
        AppUpdate(
            version = release.version.trim(),
            downloadUrl = url.toString(),
            notes = sequenceOf(release.notes, release.changelog, release.summary)
                .firstOrNull { it.isNotBlank() }?.trim()?.take(12_000).orEmpty(),
            fileSize = release.fileSizeFormatted,
        ),
    )
}

private val versionPattern = Regex("^[vV]?([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")

/** Numeric components keep the site's 1.02 format compatible with 1.2 and 1.2.0. */
internal fun compareAppVersions(first: String, second: String): Int {
    val a = requireNotNull(versionPattern.matchEntire(first.trim())) { "Invalid version" }
    val b = requireNotNull(versionPattern.matchEntire(second.trim())) { "Invalid version" }
    val aParts = a.groupValues[1].split('.').map(String::toBigInteger)
    val bParts = b.groupValues[1].split('.').map(String::toBigInteger)
    for (i in 0 until maxOf(aParts.size, bParts.size)) {
        val compared = (aParts.getOrNull(i) ?: BigInteger.ZERO).compareTo(bParts.getOrNull(i) ?: BigInteger.ZERO)
        if (compared != 0) return compared
    }
    val aPre = a.groupValues[2]
    val bPre = b.groupValues[2]
    if (aPre == bPre) return 0
    if (aPre.isEmpty()) return 1
    if (bPre.isEmpty()) return -1
    val aIds = aPre.split('.')
    val bIds = bPre.split('.')
    for (i in 0 until minOf(aIds.size, bIds.size)) {
        val x = aIds[i]
        val y = bIds[i]
        val xNumber = x.toBigIntegerOrNull()
        val yNumber = y.toBigIntegerOrNull()
        val compared = when {
            xNumber != null && yNumber != null -> xNumber.compareTo(yNumber)
            xNumber != null -> -1
            yNumber != null -> 1
            else -> x.compareTo(y)
        }
        if (compared != 0) return compared
    }
    return aIds.size.compareTo(bIds.size)
}
