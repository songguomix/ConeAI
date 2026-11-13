package com.cone.agent.memory.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionTest {

    private fun user(text: String) = ExchangeMessage("user", text)
    private fun assistant(text: String) = ExchangeMessage("assistant", text)

    @Test fun noiseIsRejected() {
        assertTrue(MemoryExtraction.isNoiseMemory("好的"))
        assertTrue(MemoryExtraction.isNoiseMemory("要不要加个缓存？"))
        assertTrue(MemoryExtraction.isNoiseMemory("I will now update the file"))
        assertFalse(MemoryExtraction.isNoiseMemory("构建用 npm run build，因为 CI 只装了 pnpm"))
    }

    @Test fun lowValueExchangeIsFiltered() {
        assertTrue(MemoryExtraction.isLowValueExchange(listOf(user("谢谢"), assistant("不客气"))))
        assertFalse(
            MemoryExtraction.isLowValueExchange(
                listOf(user("决定用 Vite 而不是 Webpack，因为构建更快"), assistant("好的，已切换")),
            ),
        )
    }

    @Test fun decisionsTriggerExtraction() {
        assertTrue(
            MemoryExtraction.shouldExtract(
                listOf(user("决定用 Vite，因为构建更快"), assistant("已切换")),
            ),
        )
        assertFalse(MemoryExtraction.shouldExtract(listOf(user("谢谢"), assistant("不客气"))))
    }

    @Test fun parseReadsJsonAndDerivesLayer() {
        val raw = """
            ```json
            {"memories":[{"title":"构建","content":"构建用 npm run build","type":"project_fact","scope":"project","tags":["build"],"importance":0.6,"confidence":0.8,"status":"confirmed","why":"CI 只装了 pnpm"}]}
            ```
        """.trimIndent()
        val parsed = MemoryExtraction.parse(raw)
        assertEquals(1, parsed.size)
        val memory = parsed.single()
        assertEquals("构建", memory.title)
        assertEquals("project_fact", memory.type)
        assertEquals("project", memory.layer)
        assertEquals("confirmed", memory.status)
        assertEquals(0.8, memory.confidence, 0.0001)
    }

    @Test fun parseDropsSecretsAndQuestions() {
        val raw = """
            {"memories":[
              {"title":"key","content":"api key 是 sk-abcdefghijklmnopqrstuvwxyz123456","type":"user_fact"},
              {"title":"q","content":"要不要加个缓存？","type":"project_fact"},
              {"title":"ok","content":"构建用 npm run build","type":"project_fact"}
            ]}
        """.trimIndent()
        val parsed = MemoryExtraction.parse(raw)
        assertEquals(1, parsed.size)
        assertEquals("构建用 npm run build", parsed.single().content)
    }

    @Test fun parseSurvivesGarbage() {
        assertTrue(MemoryExtraction.parse("no json here").isEmpty())
        assertTrue(MemoryExtraction.parse("{\"memories\": []}").isEmpty())
    }

    @Test fun requestCarriesKnownMemoryAndSignals() {
        val (system, user) = MemoryExtraction.buildRequest(
            listOf(user("决定用 Vite"), assistant("好")),
            known = listOf("注释写中文"),
            signals = MemoryExtraction.detectValueSignals(listOf(user("决定用 Vite"), assistant("好"))),
            projectName = "Cone AI",
            taskText = "决定用 Vite",
        )
        assertTrue(system.contains("memory extractor"))
        assertTrue(user.contains("Value nodes detected"))
        assertTrue(user.contains("注释写中文"))
        assertTrue(user.contains("Cone AI"))
    }
}
