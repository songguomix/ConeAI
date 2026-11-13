package com.cone.agent.memory.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTest {

    private fun record(
        id: String,
        content: String,
        layer: String = "project",
        projectId: String? = "proj",
        status: String = "confirmed",
        importance: Double = 0.7,
    ) = MemoryRecord(
        id = id,
        title = content.take(20),
        content = content,
        layer = layer,
        projectId = projectId,
        status = status,
        importance = importance,
        confidence = 0.9,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test fun scopeIsolationHolds() {
        val records = listOf(
            record("g1", "构建命令统一写进 package.json", layer = "global", projectId = null),
            record("p1", "构建用 npm run build", projectId = "proj"),
        )
        val mine = MemoryRetrieval.search(records, "构建", "proj", 8)
        assertEquals(setOf("g1", "p1"), mine.map { it.record.id }.toSet())

        val other = MemoryRetrieval.search(records, "构建", "other-project", 8)
        assertEquals(setOf("g1"), other.map { it.record.id }.toSet())
    }

    @Test fun irrelevantQueryFindsNothing() {
        val records = listOf(record("p1", "构建用 npm run build"))
        assertTrue(MemoryRetrieval.search(records, "用户喜欢什么音乐", "proj", 8).isEmpty())
    }

    @Test fun blockCarriesTrustFraming() {
        val records = listOf(record("p1", "构建用 npm run build"))
        val block = MemoryRetrieval.formatBlock(MemoryRetrieval.search(records, "构建", "proj", 8))
        assertTrue(block.contains("RELEVANT LONG-TERM MEMORY"))
        assertTrue(block.contains("verify before relying on it"))
        assertTrue(block.contains("npm run build"))
    }

    @Test fun deletedAndArchivedRecordsAreInvisible() {
        val records = listOf(
            record("p1", "构建用 npm run build").copy(deletedAt = 5),
            record("p2", "构建用 vite").copy(status = "archived"),
            record("p3", "构建用 pnpm"),
        )
        val hits = MemoryRetrieval.search(records, "构建", "proj", 8)
        assertEquals(listOf("p3"), hits.map { it.record.id })
    }

    @Test fun acknowledgementsDoNotTriggerRetrieval() {
        assertFalse(MemoryRetrieval.needsHistory("继续"))
        assertFalse(MemoryRetrieval.needsHistory("谢谢"))
        assertTrue(MemoryRetrieval.needsHistory("帮我修一下构建脚本"))
    }
}
