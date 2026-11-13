package com.cone.agent.memory.records

/**
 * Text normalisation shared by classification, full-text retrieval and dedupe — ported from
 * ConeCode.
 *
 * Chinese matters here: a whitespace tokenizer would index an entire sentence as one token and
 * make full-text search useless for most of this app's users. CJK runs become overlapping bigrams
 * (single characters for 1-char runs); Latin/number runs become lower-cased words.
 */
object MemoryText {

    private val CJK = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]")

    fun normalize(text: String): String = text
        .lowercase()
        .replace(Regex("[\u2018\u2019\u201c\u201d]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun tokenize(text: String): List<String> {
        val lower = text.lowercase()
        val tokens = ArrayList<String>()
        var i = 0
        while (i < lower.length) {
            val ch = lower[i]
            if (CJK.containsMatchIn(ch.toString())) {
                var j = i
                while (j < lower.length && CJK.containsMatchIn(lower[j].toString())) j++
                val run = lower.substring(i, j)
                if (run.length == 1) tokens += run
                for (k in 0 until run.length - 1) tokens += run.substring(k, k + 2)
                i = j
            } else if (ch.isLetterOrDigit() && ch.code < 128) {
                var j = i
                while (j < lower.length && (lower[j].isLetterOrDigit() && lower[j].code < 128 || lower[j] == '_')) j++
                val word = lower.substring(i, j)
                if (word.length > 1) tokens += word
                i = j
            } else i++
        }
        return tokens
    }

    fun termSet(text: String): Set<String> = tokenize(text).toSet()

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var shared = 0
        for (token in a) if (token in b) shared++
        return shared.toDouble() / (a.size + b.size - shared)
    }

    /** Containment: one idea phrased as a subset of another. */
    fun isContained(a: String, b: String): Boolean {
        val ka = normalize(a)
        val kb = normalize(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        if (ka == kb) return true
        val (short, long) = if (ka.length <= kb.length) ka to kb else kb to ka
        return short.length >= 8 && long.contains(short)
    }
}
