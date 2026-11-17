package com.cone.agent.data.remote

import com.cone.agent.data.remote.dto.ChatStreamChunk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FreeRelayTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun scheduledRelayPublishesTimesButHidesOfflineModels() {
        val status = parseFreeStatus(json, """
            {
              "available": false,
              "mode": "scheduled",
              "startsAt": "2026-11-18T12:00:00Z",
              "endsAt": "2026-11-19T12:00:00Z",
              "models": [{"id": "vision", "name": "Vision", "supportsVision": true}]
            }
        """.trimIndent())

        assertFalse(status.available)
        assertEquals(FreeMode.SCHEDULED, status.mode)
        assertEquals(Instant.parse("2026-11-18T12:00:00Z"), status.startsAt)
        assertEquals(Instant.parse("2026-11-19T12:00:00Z"), status.endsAt)
        assertTrue(status.models.isEmpty())
    }

    @Test
    fun catalogueKeepsValidModelsAndBounds() {
        val status = parseFreeStatus(json, """
            {
              "available": true,
              "mode": "on",
              "models": [null, 12, {"id": "", "name": "invalid"},
                {"id": "vision", "name": "Vision", "supportsVision": true, "context_window": 28000},
                {"id": "vision", "name": "duplicate"}],
              "limits": { "maxRequestBytes": 500000, "streamIdleTimeoutMs": -8 },
              "usage": { "weeklyLimit": 1200, "weeklyUsed": 20, "resetsAt": "bad date" },
              "usageResetAt": "2026-11-18T12:00:00Z"
            }
        """.trimIndent())

        assertEquals(listOf(FreeModel("vision", "Vision", true, false, 28_000, null)), status.models)
        assertEquals(500_000L, status.maxRequestBytes)
        assertEquals(120_000L, status.streamIdleTimeoutMs)
        assertEquals(1200L, status.reportedLimit)
        assertEquals(20L, status.reportedUsed)
        assertEquals(Instant.parse("2026-11-18T12:00:00Z"), status.usageResetAt)
        assertNull(status.resetsAt)
    }

    @Test
    fun weeklyCounterResetsExactlyOncePerServerMarkerAcrossWeekRollover() {
        val monday = freeWeekStart(LocalDate.parse("2026-11-16"))
        assertEquals(monday, freeWeekStart(LocalDate.parse("2026-11-22")))
        val followingMonday = freeWeekStart(LocalDate.parse("2026-11-23"))
        val reset = Instant.parse("2026-11-19T08:00:00Z")
        val staleReset = Instant.parse("2026-11-17T08:00:00Z")
        val initial = FreeUsageRecord(monday, 150, null)
        val resetRecord = initial.forWeek(monday, reset)
        assertEquals(0L, resetRecord.used)
        val used = resetRecord.forWeek(monday, reset, tokens = 40)
        assertEquals(40L, used.used)
        assertEquals(used, used.forWeek(monday, staleReset))
        assertEquals(FreeUsageRecord(followingMonday, 2, reset), used.forWeek(followingMonday, staleReset, 2))
    }

    @Test
    fun streamParsesUsageAfterFinishReasonWithoutDoneLine() = runBlocking {
        val source = Buffer().writeUtf8(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\r\n\r\n" +
                "data: {\"choices\":[],\"usage\":{\"total_tokens\":18}}",
        )
        val frames = mutableListOf<ChatStreamChunk>()

        consumeFreeStream(json, source) { frames.add(it) }

        assertEquals("Hello", frames.first().choices.first().delta?.content)
        assertEquals(18, frames.last().usage?.totalTokens)
    }

    @Test
    fun relayErrorFrameBeforeDoneIsNotTreatedAsSuccess() = runBlocking {
        val source = Buffer().writeUtf8(
            "data: {\"error\":{\"status\":429,\"message\":\"private upstream detail\"}}\n\n" +
                "data: [DONE]\n\n",
        )

        val error = runCatching { consumeFreeStream(json, source) { error("Unexpected chunk") } }.exceptionOrNull()

        assertTrue(error is InvalidFreeResponse)
        assertEquals(FreeFailure.RATE_LIMIT, (error as InvalidFreeResponse).failure)
        assertEquals(429, error.httpStatus)
        assertNull(error.message)
    }

    @Test
    fun abruptlyClosedStreamDoesNotReportDone() = runBlocking {
        val source = Buffer().writeUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"Partial\"}}]}\n")

        val error = runCatching { consumeFreeStream(json, source) {} }.exceptionOrNull()

        assertTrue(error is InvalidFreeResponse)
    }
}
