package com.cone.agent.memory.records

/**
 * Active retrieval, ported from ConeCode's `retrieval.ts`.
 *
 * The legacy behaviour was "take every memory, group by kind, paste into the system prompt".
 * Here retrieval is a real ranked query — BM25 full text over title/tags/body, filtered by
 * project scope, then ranked with importance, confidence, recency and confirmation — so only the
 * memories relevant to *this* task reach the prompt.
 */
object MemoryRetrieval {

    private const val K1 = 1.5
    private const val B = 0.75

    data class Hit(
        val record: MemoryRecord,
        val score: Double,
        val matchedTerms: List<String> = emptyList(),
        val reasons: List<String> = emptyList(),
    )

    private data class Doc(val record: MemoryRecord, val terms: List<String>)

    private data class Index(
        val docs: List<Doc>,
        val df: Map<String, Int>,
        val avgLength: Double,
        val size: Int,
    )

    private fun buildIndex(records: List<MemoryRecord>): Index {
        val docs = records.map { record ->
            // Field repetition is the boost: title and tags count more than body text.
            val terms = ArrayList<String>()
            terms += MemoryText.tokenize(record.title)
            terms += MemoryText.tokenize(record.title)
            terms += MemoryText.tokenize(record.tags.joinToString(" "))
            terms += MemoryText.tokenize(record.tags.joinToString(" "))
            terms += MemoryText.tokenize(record.content)
            terms += MemoryText.tokenize(record.why.orEmpty())
            Doc(record, terms)
        }
        val df = HashMap<String, Int>()
        for (doc in docs) for (term in doc.terms.toSet()) df[term] = (df[term] ?: 0) + 1
        val total = docs.sumOf { it.terms.size }
        return Index(docs, df, if (docs.isEmpty()) 0.0 else total.toDouble() / docs.size, docs.size)
    }

    private fun bm25(index: Index, doc: Doc, queryTerms: List<String>): Pair<Double, List<String>> {
        if (queryTerms.isEmpty() || doc.terms.isEmpty()) return 0.0 to emptyList()
        val tf = HashMap<String, Int>()
        for (term in doc.terms) tf[term] = (tf[term] ?: 0) + 1
        var score = 0.0
        val matched = ArrayList<String>()
        for (term in queryTerms.toSet()) {
            val freq = tf[term] ?: continue
            matched += term
            val n = index.df[term] ?: 0
            val idf = kotlin.math.ln(1 + (index.size - n + 0.5) / (n + 0.5))
            val denom = freq + K1 * (1 - B + B * doc.terms.size / (index.avgLength.takeIf { it > 0 } ?: 1.0))
            score += idf * (freq * (K1 + 1)) / denom
        }
        return score to matched
    }

    /** Recency in [0,1] with a ~90-day half-life. Older isn't worthless, just softer. */
    fun recencyScore(updatedAt: Long, now: Long): Double {
        val ageDays = maxOf(0.0, (now - updatedAt) / 86_400_000.0)
        return 1 / (1 + ageDays / 90)
    }

    fun rankScore(record: MemoryRecord, score: Double, now: Long): Double =
        minOf(1.0, score / 6) * 0.55 +
            record.importance * 0.2 +
            record.confidence * 0.12 +
            recencyScore(record.updatedAt, now) * 0.08 +
            (if (record.status == "confirmed") 0.05 else 0.0)

    /** Hard project isolation: global records or the current project, nothing else. */
    fun visibleToProject(record: MemoryRecord, projectId: String?): Boolean {
        if (record.deletedAt != null) return false
        if (record.layer == "global") return true
        return projectId != null && record.projectId == projectId
    }

    fun search(
        records: List<MemoryRecord>,
        text: String,
        projectId: String?,
        limit: Int = 8,
        now: Long = System.currentTimeMillis(),
        includeArchived: Boolean = false,
    ): List<Hit> {
        val pool = records.filter { record ->
            visibleToProject(record, projectId) &&
                (includeArchived || (record.status != "archived" && record.status != "superseded"))
        }
        if (pool.isEmpty()) return emptyList()
        val queryTerms = MemoryText.tokenize(text)
        val index = buildIndex(pool)
        val browsing = text.trim().isEmpty()
        val hits = if (browsing) {
            pool.map { Hit(it, 0.0, reasons = listOf("browse")) }
        } else {
            index.docs.map { doc ->
                val (score, matched) = bm25(index, doc, queryTerms)
                val reasons = ArrayList<String>()
                if (matched.isNotEmpty()) reasons += "full-text: " + matched.take(4).joinToString(", ")
                if (doc.record.status == "confirmed") reasons += "user-confirmed"
                if (doc.record.importance >= 0.8) reasons += "high importance"
                Hit(doc.record, score, matched, reasons)
            }.filter { it.score > 0 }
        }
        return hits.sortedByDescending { rankScore(it.record, it.score, now) }.take(limit)
    }

    fun formatBlock(hits: List<Hit>, maxChars: Int = 6000): String {
        val usable = hits.filter { it.record.deletedAt == null && it.record.status != "archived" }
        if (usable.isEmpty()) return ""
        val lines = ArrayList<String>()
        lines += "RELEVANT LONG-TERM MEMORY (retrieved for this task, not a chat summary)."
        lines += "Use it silently. A memory marked \"inferred\" is an agent conclusion — verify before " +
            "relying on it. If the user contradicts a memory, the user wins."
        var used = lines.sumOf { it.length }
        for (hit in usable) {
            val record = hit.record
            val meta = "[${record.layer}/${record.type} | ${record.status} | v${record.version} | " +
                "importance ${"%.2f".format(record.importance)}]"
            val source = record.sourceSessionId?.let { " | session ${it.take(8)}" } ?: ""
            val why = record.why?.let { "\n  reason: ${it.take(300)}" } ?: ""
            val line = "- $meta$source\n  ${record.title}: ${record.content.take(900)}$why"
            if (used + line.length > maxChars) break
            lines += line
            used += line.length + 1
        }
        return if (lines.size > 2) lines.joinToString("\n") else ""
    }

    /** Task-start decision: does this task need history at all? */
    fun needsHistory(taskText: String): Boolean {
        if (taskText.isBlank()) return false
        return !Regex(
            "^(ok|okay|thanks|thank you|好的|谢谢|收到|嗯|继续|hi|hello|你好|您好|嗨|在吗|早上好|晚上好)[\\s\\W!！.。~]*$",
            RegexOption.IGNORE_CASE,
        ).matches(taskText.trim())
    }
}
