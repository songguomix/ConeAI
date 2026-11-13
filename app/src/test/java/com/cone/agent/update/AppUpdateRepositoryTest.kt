package com.cone.agent.update

import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class AppUpdateRepositoryTest {
    private val downloadUrl = "https://agibase.dev/api/download/release-id"

    private fun response(
        version: String = "1.03",
        platform: String = "Android",
        fileName: String = "ConeAI-$version.apk",
        url: String = downloadUrl,
        product: String = "coneai",
        available: Boolean = true,
    ): String = """
        {
          "status":"ok", "update_available":$available,
          "product":{"slug":"$product", "name":"ConeAI"},
          "latest":{
            "version":"$version", "platform":"$platform", "fileName":"$fileName",
            "downloadUrl":"$url", "notes":"Release notes", "fileSizeFormatted":"40 MB"
          },
          "future_field":"ignored"
        }
    """.trimIndent()

    @Test fun newerAndroidReleaseIncludesDownloadAndNotes() {
        val result = parseUpdateResponse(response(), "1.02") as UpdateCheckResult.Available
        assertEquals("1.03", result.update.version)
        assertEquals(downloadUrl, result.update.downloadUrl)
        assertEquals("Release notes", result.update.notes)
        assertEquals("40 MB", result.update.fileSize)
    }

    @Test fun sameOrOlderVersionsNeverOfferADowngradeEvenWhenServerSaysUpdate() {
        for (version in listOf("1.02", "v1.2.0", "1.01", "1.2-beta.1")) {
            assertEquals(UpdateCheckResult.UpToDate, parseUpdateResponse(response(version), "1.02"))
        }
    }

    @Test fun numericComparisonAndReleaseOrdering() {
        assertTrue(compareAppVersions("1.10", "1.9") > 0)
        assertTrue(compareAppVersions("2.0", "1.99") > 0)
        assertEquals(0, compareAppVersions(" v1.02.0 ", "1.2"))
        assertEquals(0, compareAppVersions("1.2+build.2", "1.2+build.1"))
        assertTrue(compareAppVersions("1.2", "1.2-rc.1") > 0)
        assertTrue(compareAppVersions("1.2-beta.10", "1.2-beta.2") > 0)
    }

    @Test fun desktopFallbackAndNonApkFilesAreNotMobileUpdates() {
        assertEquals(UpdateCheckResult.NoRelease, parseUpdateResponse(response("2.4.11", "macOS", "ConeCode.dmg"), "1.02"))
        assertEquals(UpdateCheckResult.NoRelease, parseUpdateResponse(response(fileName = "sources.zip"), "1.02"))
        assertEquals(UpdateCheckResult.NoRelease, parseUpdateResponse("""{"status":"no_update","update_available":false}""", "1.02"))
    }

    @Test fun platformAliasesAcceptedByTheServerRemainMobileUpdates() {
        for (platform in listOf("Android", " android ", "APK", "Android arm64")) {
            assertTrue(parseUpdateResponse(response(platform = platform), "1.02") is UpdateCheckResult.Available)
        }
    }

    @Test fun updateIsDerivedFromVersionsEvenIfServerFlagIsFalse() {
        assertTrue(parseUpdateResponse(response(available = false), "1.02") is UpdateCheckResult.Available)
    }

    @Test fun malformedAndWrongProductResponsesFailInsteadOfClaimingLatest() {
        for (body in listOf("not json", "{}", """{"status":"error","message":"unknown product"}""", response(product = "conecode"), response(version = "invalid"))) {
            assertFails { parseUpdateResponse(body, "1.02") }
        }
    }

    @Test fun downloadMustUseOfficialHttpsDownloadEndpoint() {
        for (url in listOf("http://agibase.dev/api/download/id", "https://other.example/api/download/id", "https://agibase.dev.evil.example/api/download/id", "https://agibase.dev/", "https://agibase.dev/api/download/", "https://user@agibase.dev/api/download/id")) {
            assertFails { parseUpdateResponse(response(url = url), "1.02") }
        }
    }

    @Test fun fallsBackToAndroidRecordWhenLatestIsForDesktop() {
        val root = Json.parseToJsonElement(response("2.4.11", "macOS", "app.dmg")) as JsonObject
        val android = (Json.parseToJsonElement(response()) as JsonObject).getValue("latest")
        val body = JsonObject(root + ("all_platforms" to JsonArray(listOf(android)))).toString()
        assertTrue(parseUpdateResponse(body, "1.02") is UpdateCheckResult.Available)
    }

    @Test fun requestTargetsMobileProductAndSendsCurrentVersion() = runBlocking {
        var requested = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https", request.url.scheme)
            assertEquals("agibase.dev", request.url.host)
            assertEquals("/api/update", request.url.encodedPath)
            assertEquals("coneai", request.url.queryParameter("product"))
            assertEquals("android", request.url.queryParameter("platform"))
            assertEquals("1.02", request.url.queryParameter("version"))
            requested = true
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response().toResponseBody()).build()
        }.build()
        assertTrue(AppUpdateRepository(client).check("1.02") is UpdateCheckResult.Available)
        assertTrue(requested)
    }

    @Test fun httpFailureDoesNotBecomeUpToDate() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("maintenance".toResponseBody()).build()
        }.build()
        try {
            AppUpdateRepository(client).check("1.02")
            fail("HTTP failure must propagate")
        } catch (_: IOException) { }
    }

    @Test fun cancellingCheckCancelsTheHttpCall() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<okhttp3.Call>()
        val release = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            throw IOException("Canceled")
        }.build()
        val job = launch {
            AppUpdateRepository(client).check("1.02")
        }
        try {
            val call = kotlinx.coroutines.withTimeout(5_000) { started.await() }
            job.cancel()
            job.join()
            assertTrue(call.isCanceled())
        } finally {
            job.cancel()
            release.countDown()
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid update metadata to be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
