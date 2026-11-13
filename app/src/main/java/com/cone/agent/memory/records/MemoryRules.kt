package com.cone.agent.memory.records

import java.security.MessageDigest

/**
 * Classification and quality control, ported from ConeCode's `classify.ts` + `model.ts`.
 *
 * Classification is deterministic where it can be (type -> layer, scope -> projectId) and only
 * leaves the genuinely semantic parts to the model. Quality control then decides
 * add / reinforce / merge / reconcile / reject before anything is written.
 */
object MemoryRules {

    private val SECRET_PATTERNS = listOf(
        Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
        Regex("\\bsk-[A-Za-z0-9_-]{20,}"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
        Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
        Regex(
            "(?:password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\s*[:=]\\s*[^\\s\"',;]{6,}",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val NOISE = Regex("^(好的|收到|谢谢|ok|okay|继续|嗯|明白|了解|thanks|sure|got it|lgtm)[\\s\\W!！.。]*$", RegexOption.IGNORE_CASE)

    private val REASON = Regex("(?:because|since|so that|因此|因为|原因|避免|导致|否则|由于)")
    private val CODEY = Regex("[`/_.\\-()]|\\b(?:src|test|api|db|sql|http|config|build|deploy|module)\\b", RegexOption.IGNORE_CASE)
    private val NEGATIVE = Regex(
        "(?:^|\\b)(?:not|never|don't|do not|avoid|disable[ds]?|remove[ds]?|delete[ds]?|without)(?:\\b|$)|不要|别(?:再)?|禁止|禁用|关闭|删除|移除|避免|从不|不能|不可|无需",
        RegexOption.IGNORE_CASE,
    )
    private val POSITIVE = Regex(
        "(?:^|\\b)(?:always|must|use[ds]?|enable[ds]?|keep|retain|add(?:ed)?|required?|allow(?:ed)?)(?:\\b|$)|必须|总是|始终|使用|启用|开启|保留|添加|可以|允许|务必",
        RegexOption.IGNORE_CASE,
    )

    const val SIMILAR_THRESHOLD = 0.62
    const val MIN_QUALITY = 0.4
    const val DEFAULT_IMPORTANCE = 0.5
    const val DEFAULT_CONFIDENCE = 0.65

    fun looksLikeSecret(text: String): Boolean = SECRET_PATTERNS.any { it.containsMatchIn(text) }

    /** One-line title derived from the fact itself, used when the model omits one. */
    fun deriveTitle(content: String, max: Int = 60): String {
        val first = content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val sentence = first.split(Regex("[。.!！?？]")).firstOrNull().orEmpty().trim().ifBlank { first }
        return sentence.take(max)
    }

    /** 0 = worthless, 1 = clearly durable. */
    fun qualityScore(title: String, content: String, tags: List<String>, why: String?, importance: Double?): Double {
        val text = "$title $content".trim()
        if (text.length < 12 || NOISE.matches(text) || MemoryExtraction.isNoiseMemory(content)) return 0.0
        var score = 0.35
        if (text.length >= 30) score += 0.1
        if (REASON.containsMatchIn(text)) score += 0.15
        if (CODEY.containsMatchIn(text)) score += 0.12
        if (!why.isNullOrBlank()) score += 0.12
        if (tags.size >= 2) score += 0.06
        if (importance != null) score += minOf(0.1, importance * 0.1)
        return minOf(1.0, score)
    }

    fun layerFor(type: String, scope: String): String {
        if (scope == "global" || type == "user_fact") return "global"
        if (type == "task_result" || type == "success_experience" || type == "failure_experience") return "episodic"
        if (type == "reusable_knowledge" || type == "decision_reason") return "semantic"
        if (type == "todo" || type == "progress") return "working"
        return "project"
    }

    /** A classified candidate, before dedupe. */
    data class Classified(
        val title: String,
        val content: String,
        val type: String,
        val layer: String,
        val projectId: String?,
        val tags: List<String>,
        val importance: Double,
        val confidence: Double,
        val status: String,
        val why: String?,
    )

    fun classify(parsed: ParsedMemory, projectId: String?, userStated: Boolean): Classified {
        val layer = layerFor(parsed.type, parsed.scope)
        val project = if (layer == "global") null else projectId
        val status = when {
            userStated -> "confirmed"
            parsed.status == "confirmed" -> "confirmed"
            parsed.status == "pending" -> "pending"
            else -> "inferred"
        }
        val confidence = if (userStated) maxOf(0.9, parsed.confidence) else parsed.confidence
        // A project-bound memory with no project cannot be stored: demote to global rather than
        // silently attaching it to the wrong workspace.
        val safeLayer = if (layer != "global" && project == null) "global" else layer
        val tags = LinkedHashSet<String>().apply {
            addAll(parsed.tags)
            parsed.outcome?.let { add(it) }
            add(parsed.type)
        }.toList()
        return Classified(
            title = parsed.title,
            content = parsed.content,
            type = parsed.type,
            layer = safeLayer,
            projectId = if (safeLayer == "global") null else project,
            tags = tags,
            importance = parsed.importance,
            confidence = confidence,
            status = status,
            why = parsed.why,
        )
    }

    /** Only fires when two similar memories sit on opposite sides of a rule. */
    fun detectContradiction(a: String, b: String): Boolean {
        val aNeg = NEGATIVE.containsMatchIn(a)
        val bNeg = NEGATIVE.containsMatchIn(b)
        if (aNeg == bNeg) return false
        val aPos = POSITIVE.containsMatchIn(a)
        val bPos = POSITIVE.containsMatchIn(b)
        return (aNeg && bPos) || (bNeg && aPos)
    }

    sealed interface Decision {
        data class Add(val candidate: Classified) : Decision
        data class Reinforce(val candidate: Classified, val existing: MemoryRecord) : Decision
        data class Merge(val candidate: Classified, val existing: MemoryRecord) : Decision
        data class Reconcile(val candidate: Classified, val existing: MemoryRecord) : Decision
        data class Reject(val candidate: Classified, val reason: String) : Decision
    }

    fun dedupe(candidates: List<Classified>, existing: List<MemoryRecord>): List<Decision> {
        val active = existing.filter { it.deletedAt == null && it.status != "archived" }
        val seen = HashSet<String>()
        val decisions = ArrayList<Decision>()
        for (candidate in candidates) {
            val key = "${candidate.layer}:${candidate.projectId.orEmpty()}:${candidate.content}"
            if (!seen.add(key)) {
                decisions += Decision.Reject(candidate, "duplicate within this extraction")
                continue
            }
            if (qualityScore(candidate.title, candidate.content, candidate.tags, candidate.why, candidate.importance) < MIN_QUALITY) {
                decisions += Decision.Reject(candidate, "below quality threshold")
                continue
            }
            val pool = active.filter { it.layer == candidate.layer && it.projectId == candidate.projectId }
            val candidateTerms = MemoryText.termSet("${candidate.title} ${candidate.content}")
            var best: MemoryRecord? = null
            var bestSimilarity = 0.0
            for (record in pool) {
                val recordTerms = MemoryText.termSet("${record.title} ${record.content}")
                val similarity = maxOf(
                    MemoryText.jaccard(candidateTerms, recordTerms),
                    if (MemoryText.isContained(candidate.content, record.content)) 0.9 else 0.0,
                )
                if (similarity > bestSimilarity) {
                    best = record
                    bestSimilarity = similarity
                }
            }
            if (best != null && bestSimilarity >= SIMILAR_THRESHOLD) {
                // Opposite rules on the same subject are a correction, not a restatement.
                if (detectContradiction(candidate.content, best.content)) {
                    decisions += Decision.Reconcile(candidate, best)
                    continue
                }
                val sharper = candidate.content.length > best.content.length * 1.15
                decisions += if (sharper) Decision.Merge(candidate, best) else Decision.Reinforce(candidate, best)
                continue
            }
            decisions += Decision.Add(candidate)
        }
        return decisions
    }

    fun contentHash(record: MemoryRecord): String {
        val material = listOf(
            record.title, record.content, record.type, record.layer,
            record.projectId.orEmpty(), record.tags.joinToString(","), record.status,
            record.why.orEmpty(),
        ).joinToString("\u0001")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun newId(): String = "m-" + System.currentTimeMillis().toString(36) + "-" +
        (0..5).map { ('a'..'z').random() }.joinToString("")

    fun snapshot(record: MemoryRecord) = MemoryVersion(
        version = record.version,
        title = record.title,
        content = record.content,
        updatedAt = record.updatedAt,
        status = record.status,
        why = record.why,
        importance = record.importance,
        confidence = record.confidence,
    )
}
