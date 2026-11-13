package com.cone.agent.memory.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLifecycleTest {

    private fun record(
        content: String = "构建用 npm run build",
        importance: Double = 0.5,
        confidence: Double = 0.65,
        status: String = "inferred",
        origin: String = "agent",
        updatedAt: Long = 1000,
    ) = MemoryRecord(
        id = "r1",
        title = "构建",
        content = content,
        layer = "project",
        projectId = "proj",
        importance = importance,
        confidence = confidence,
        status = status,
        origin = origin,
        createdAt = 1000,
        updatedAt = updatedAt,
    )

    @Test fun updateKeepsVersionHistory() {
        val updated = MemoryLifecycle.update(
            record(),
            MemoryLifecycle.Patch(content = "构建用 pnpm", status = "confirmed"),
            2000,
        )
        assertEquals(2, updated.version)
        assertEquals(2000L, updated.updatedAt)
        assertEquals("构建用 pnpm", updated.content)
        assertEquals("confirmed", updated.status)
        assertEquals(1, updated.history.size)
        assertEquals("构建用 npm run build", updated.history.single().content)
    }

    @Test fun reinforceRespectsProtectedImportance() {
        val protected = record(importance = 0.95)
        assertEquals(protected, MemoryLifecycle.reinforce(protected, 2000))
        val normal = MemoryLifecycle.reinforce(record(importance = 0.5), 2000)
        assertTrue(normal.importance > 0.5)
    }

    @Test fun reconcileLetsUserStatedWin() {
        val existing = record(confidence = 0.65, status = "inferred", origin = "agent")
        val incoming = MemoryRules.Classified(
            title = "构建",
            content = "构建改用 pnpm",
            type = "project_fact",
            layer = "project",
            projectId = "proj",
            tags = listOf("project_fact"),
            importance = 0.7,
            confidence = 0.95,
            status = "confirmed",
            why = "用户确认",
        )
        val (updated, outcome) = MemoryLifecycle.reconcile(existing, incoming, 2000)
        assertEquals("superseded", outcome)
        assertEquals("构建改用 pnpm", updated.content)
    }

    @Test fun reconcileKeepsBothWhenUnclear() {
        val existing = record(confidence = 0.9, status = "confirmed", origin = "user")
        val incoming = MemoryRules.Classified(
            title = "构建",
            content = "构建改用 pnpm",
            type = "project_fact",
            layer = "project",
            projectId = "proj",
            tags = emptyList(),
            importance = 0.6,
            confidence = 0.7,
            status = "inferred",
            why = null,
        )
        val (updated, outcome) = MemoryLifecycle.reconcile(existing, incoming, 2000)
        assertEquals("kept_both", outcome)
        assertEquals(existing.content, updated.content)
    }

    @Test fun tombstoneIsSoftDelete() {
        val tomb = MemoryLifecycle.tombstone(record(), 3000)
        assertEquals(3000L, tomb.deletedAt)
        assertTrue(!tomb.isActive)
        assertNotNull(tomb.history)
    }

    @Test fun undoRestoresPreviousVersion() {
        val updated = MemoryLifecycle.update(record(), MemoryLifecycle.Patch(content = "构建用 pnpm"), 2000)
        val undone = MemoryLifecycle.undo(updated, 3000)
        assertNotNull(undone)
        assertEquals("构建用 npm run build", undone!!.content)
        assertNull(MemoryLifecycle.undo(record(), 3000))
    }
}
