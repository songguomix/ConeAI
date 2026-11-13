package com.cone.agent.memory.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTextTest {

    @Test fun latinWordsAreTokenised() {
        val tokens = MemoryText.tokenize("Use Vite with pnpm for the build")
        assertTrue(tokens.contains("use"))
        assertTrue(tokens.contains("vite"))
        assertTrue(tokens.contains("pnpm"))
    }

    @Test fun cjkRunsBecomeOverlappingBigrams() {
        val tokens = MemoryText.tokenize("项目使用 Vue")
        assertTrue(tokens.contains("项目"))
        assertTrue(tokens.contains("目使"))
        assertTrue(tokens.contains("使用"))
        assertTrue(tokens.contains("vue"))
    }

    @Test fun jaccardIsSymmetric() {
        val a = MemoryText.termSet("npm run build")
        val b = MemoryText.termSet("npm run test")
        assertEquals(MemoryText.jaccard(a, b), MemoryText.jaccard(b, a), 0.0001)
        assertTrue(MemoryText.jaccard(a, b) > 0.0)
    }

    @Test fun containmentDetectsSubsetWording() {
        assertTrue(MemoryText.isContained("所有注释一律写中文", "所有注释一律写中文，不要用英文"))
        assertFalse(MemoryText.isContained("用 Vue", "用 React"))
        // Too short to be a reliable subset: a 5-char phrase is not evidence of the same idea.
        assertFalse(MemoryText.isContained("注释写中文", "所有注释一律写中文，不要用英文"))
    }
}
