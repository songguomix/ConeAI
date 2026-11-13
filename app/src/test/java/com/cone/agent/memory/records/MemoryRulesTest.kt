package com.cone.agent.memory.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRulesTest {

    private fun candidate(
        content: String,
        type: String = "project_fact",
        layer: String = "project",
        projectId: String? = "proj",
        importance: Double = 0.6,
        confidence: Double = 0.7,
        status: String = "inferred",
        why: String? = null,
    ) = MemoryRules.Classified(
        title = MemoryRules.deriveTitle(content),
        content = content,
        type = type,
        layer = layer,
        projectId = projectId,
        tags = listOf(type),
        importance = importance,
        confidence = confidence,
        status = status,
        why = why,
    )

    private fun record(
        content: String,
        layer: String = "project",
        projectId: String? = "proj",
        importance: Double = 0.5,
        confidence: Double = 0.65,
        status: String = "inferred",
        origin: String = "agent",
        updatedAt: Long = 1000,
    ) = MemoryRecord(
        id = "r-${content.hashCode()}",
        title = MemoryRules.deriveTitle(content),
        content = content,
        layer = layer,
        projectId = projectId,
        importance = importance,
        confidence = confidence,
        status = status,
        origin = origin,
        createdAt = 1000,
        updatedAt = updatedAt,
    )

    @Test fun secretsAreDetected() {
        assertTrue(MemoryRules.looksLikeSecret("token sk-abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(MemoryRules.looksLikeSecret("password: hunter2hunter2"))
        assertFalse(MemoryRules.looksLikeSecret("构建命令是 npm run build"))
    }

    @Test fun qualityRejectsNoise() {
        assertEquals(0.0, MemoryRules.qualityScore("好的", "好的", emptyList(), null, 0.5), 0.0001)
        assertTrue(MemoryRules.qualityScore("构建", "构建命令是 npm run build，因为 CI 只装了 pnpm", listOf("a", "b"), "CI", 0.8) > 0.6)
    }

    @Test fun layerFollowsType() {
        assertEquals("global", MemoryRules.layerFor("user_fact", "project"))
        assertEquals("episodic", MemoryRules.layerFor("task_result", "project"))
        assertEquals("semantic", MemoryRules.layerFor("decision_reason", "project"))
        assertEquals("working", MemoryRules.layerFor("todo", "project"))
        assertEquals("project", MemoryRules.layerFor("project_fact", "project"))
    }

    @Test fun classifyPromotesUserStatedFacts() {
        val parsed = ParsedMemory(
            title = "注释语言",
            content = "注释写中文",
            type = "user_fact",
            layer = "global",
            scope = "global",
            tags = emptyList(),
            importance = 0.7,
            confidence = 0.6,
            status = "inferred",
            why = null,
            outcome = null,
        )
        val classified = MemoryRules.classify(parsed, projectId = "proj", userStated = true)
        assertEquals("global", classified.layer)
        assertNull(classified.projectId)
        assertEquals("confirmed", classified.status)
        assertTrue(classified.confidence >= 0.9)
    }

    @Test fun classifyDemotesProjectFactWithoutProject() {
        val parsed = ParsedMemory(
            title = "构建",
            content = "构建用 npm run build",
            type = "project_fact",
            layer = "project",
            scope = "project",
            tags = emptyList(),
            importance = 0.5,
            confidence = 0.7,
            status = "inferred",
            why = null,
            outcome = null,
        )
        val classified = MemoryRules.classify(parsed, projectId = null, userStated = false)
        assertEquals("global", classified.layer)
        assertNull(classified.projectId)
    }

    @Test fun dedupeAddsWhenNothingSimilar() {
        val decisions = MemoryRules.dedupe(
            listOf(candidate("构建命令是 npm run build，因为 CI 只装了 pnpm")),
            emptyList(),
        )
        assertTrue(decisions.single() is MemoryRules.Decision.Add)
    }

    @Test fun dedupeReinforcesNearDuplicate() {
        val existing = record("构建命令是 npm run build，因为 CI 只装了 pnpm")
        val decisions = MemoryRules.dedupe(
            listOf(candidate("构建命令是 npm run build，因为 CI 只装了 pnpm")),
            listOf(existing),
        )
        assertTrue(decisions.single() is MemoryRules.Decision.Reinforce)
    }

    @Test fun dedupeMergesSharperRestatement() {
        val existing = record("构建用 npm run build")
        val decisions = MemoryRules.dedupe(
            listOf(candidate("构建用 npm run build，因为 CI 只装了 pnpm，别用 yarn")),
            listOf(existing),
        )
        assertTrue(decisions.single() is MemoryRules.Decision.Merge)
    }

    @Test fun dedupeReconcilesOppositeRules() {
        val existing = record("部署必须使用 Docker")
        val decisions = MemoryRules.dedupe(
            listOf(candidate("部署必须使用 Docker，但不要用 root 用户跑")),
            listOf(existing),
        )
        assertTrue(decisions.single() is MemoryRules.Decision.Reconcile)
    }

    @Test fun dedupeRejectsLowQuality() {
        val decisions = MemoryRules.dedupe(listOf(candidate("好的")), emptyList())
        assertTrue(decisions.single() is MemoryRules.Decision.Reject)
    }

    @Test fun deriveTitleTakesFirstSentence() {
        assertEquals("构建用 npm", MemoryRules.deriveTitle("构建用 npm。另外还有别的"))
    }
}
