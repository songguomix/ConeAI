package com.cone.agent.memory.records

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One message of the exchange being considered for memory. */
data class ExchangeMessage(val role: String, val content: String)

/** Why this exchange might be worth remembering — no timer involved. */
data class ValueSignal(val kind: String, val weight: Double, val evidence: String)

/** One candidate the model proposed, already normalised. */
data class ParsedMemory(
    val title: String,
    val content: String,
    val type: String,
    val layer: String,
    val scope: String,
    val tags: List<String>,
    val importance: Double,
    val confidence: Double,
    val status: String,
    val why: String?,
    val outcome: String?,
)

/**
 * Value-node extraction, ported from ConeCode's `extract.ts`.
 *
 * The legacy extractor ran on a fixed cadence and summarised the last N messages, which wasted
 * provider calls on small talk and missed one-off decisions. Here extraction is triggered by
 * events with long-term value, and a cheap deterministic pre-filter runs before any model call.
 */
object MemoryExtraction {

    private val json = Json { ignoreUnknownKeys = true }

    private val DECISION = Regex("(决定|就用|采用|确认|拍板|approved?|confirmed|decided|let'?s (use|go with)|we'?ll use|改用|定为)", RegexOption.IGNORE_CASE)
    private val ARCH = Regex("(架构|重构|migrat|refactor|模块拆分|技术选型|stack|schema change|redesign)", RegexOption.IGNORE_CASE)
    private val SOLVED = Regex("(已修复|修复了|resolved|fixed|workaround|root cause|根因|解决(了|方案))", RegexOption.IGNORE_CASE)
    private val DONE = Regex("(完成|已完成|测试通过|tests? pass|verified|done and|shipped|merged)", RegexOption.IGNORE_CASE)
    private val REUSABLE = Regex("(以后|每次|总是|永远|不要|务必|always|never|remember|经验|复用|踩坑|规律|convention)", RegexOption.IGNORE_CASE)
    private val FAILED = Regex("(失败|报错|error|failed|regression|回滚|rollback|could not|unable to)", RegexOption.IGNORE_CASE)
    private val CHITCHAT = Regex("^(谢谢|thanks|ok|okay|好的|嗯|hi|hello|hey|再见|bye|收到|👍|👌)[\\s\\W!！.。~]*$", RegexOption.IGNORE_CASE)
    private val NAVIGATION = Regex("^(open|close|show|hide|打开|关闭|显示|隐藏|切换|继续|再来|好的|收到)[\\s\\W]*$", RegexOption.IGNORE_CASE)
    private val PROCESS_NARRATION = Regex(
        "^(i (will|am going to|'ll)|i'll|let me|let's|we (will|need to|are going to|'ll)|next[, ]|now i|then i)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val PROCESS_NARRATION_ZH = Regex("^(我来|我会|接下来|下一步|现在我们|先来|然后我们|继续处理|正在处理)")
    private val RECAP = Regex("^(we|i) (looked|reviewed|checked|discussed|went through)\\b", RegexOption.IGNORE_CASE)
    private val RECAP_REASON = Regex("(because|so that|therefore|decided|conclusion|因为|所以|因此|决定|结论|避免|否则)", RegexOption.IGNORE_CASE)

    /**
     * Strict hygiene for anything that would become a durable memory: models like to return
     * process narration or unanswered questions as if they were knowledge.
     */
    fun isNoiseMemory(content: String): Boolean {
        val text = content.replace(Regex("\\s+"), " ").trim()
        if (text.length < 8) return true
        if (CHITCHAT.matches(text)) return true
        // A question is not a fact, a decision, or a lesson.
        if (Regex("[?？]\\s*$").containsMatchIn(text)) return true
        if (PROCESS_NARRATION.containsMatchIn(text)) return true
        if (PROCESS_NARRATION_ZH.containsMatchIn(text)) return true
        if (RECAP.containsMatchIn(text) && !RECAP_REASON.containsMatchIn(text)) return true
        return false
    }

    fun isSubstantiveTask(text: String): Boolean {
        val value = text.replace(Regex("\\s+"), " ").trim()
        if (value.length < 8) return false
        if (CHITCHAT.matches(value)) return false
        if (NAVIGATION.matches(value)) return false
        return true
    }

    fun detectValueSignals(messages: List<ExchangeMessage>): List<ValueSignal> {
        val out = ArrayList<ValueSignal>()
        fun push(kind: String, weight: Double, evidence: String) {
            if (out.none { it.kind == kind }) out += ValueSignal(kind, weight, evidence.take(140))
        }
        val userText = messages.filter { it.role == "user" }.joinToString("\n") { it.content }
        val allText = messages.joinToString("\n") { it.content }
        DECISION.find(userText)?.let { push("user_decision", 1.0, it.value) }
        ARCH.find(allText)?.let { push("architecture_change", 0.9, it.value) }
        SOLVED.find(allText)?.let { push("problem_solved", 0.9, it.value) }
        DONE.find(allText)?.let { push("task_completed", 0.7, it.value) }
        REUSABLE.find(allText)?.let { push("reusable_experience", 0.8, it.value) }
        FAILED.find(allText)?.let { push("task_failed", 0.85, "failure detected") }
        return out
    }

    /** A turn is extracted only when a value node is present and the user said something. */
    fun shouldExtract(messages: List<ExchangeMessage>): Boolean {
        if (messages.none { it.role == "user" && it.content.trim().isNotEmpty() }) return false
        val best = detectValueSignals(messages).maxOfOrNull { it.weight } ?: 0.0
        // A single "done" (0.7) is not enough on its own; decisions/failures/solutions are.
        return best >= 0.8
    }

    /** Cheap pre-filter: no point spending a provider call on "thanks!". */
    fun isLowValueExchange(messages: List<ExchangeMessage>): Boolean {
        val meaningful = messages.filter { it.content.trim().isNotEmpty() }
        if (meaningful.isEmpty()) return true
        if (meaningful.sumOf { it.content.length } < 24) return true
        return meaningful.all { CHITCHAT.matches(it.content.trim()) }
    }

    const val EXTRACTION_PROMPT = """You are the memory extractor for a long-running coding agent. You do NOT summarise chat. You decide whether this exchange produced knowledge worth reusing weeks from now, and if so emit structured records.

Reply with JSON only:
{"memories":[{"title":"short label","content":"one durable fact WITH the necessary technical detail and decision context","type":"...","scope":"global|project","tags":["..."],"importance":0.0-1.0,"confidence":0.0-1.0,"status":"confirmed|inferred|pending","why":"evidence or reason, when known","outcome":"success|failure"}]}

type must be one of: user_fact, project_fact, architecture, technical_decision, decision_reason, constraint, task_result, progress, todo, success_experience, failure_experience, known_bug, reusable_knowledge.
layer is derived by the engine from type + scope (available layers: global, project, episodic, semantic, working).

Extract ONLY these value nodes:
- A user explicitly confirmed a decision, preference or constraint. status="confirmed", confidence>=0.9.
- A technical decision AND its reason (emit the decision; put the rationale in why).
- A project architecture/stack/constraint change.
- A problem was solved, or a task failed: emit the reusable lesson, the symptom, the root cause and the resolution. Use type failure_experience / success_experience / known_bug, and outcome.
- Current progress and unfinished TODOs that a later session would need.
- A reusable pattern observed across tasks.

Hard rules:
- Return {"memories":[]} unless something is genuinely durable. Most exchanges produce nothing. Never invent.
- NEVER store secrets, API keys, tokens, passwords, file contents, or raw transcripts.
- Do not restate small talk, greetings, or step-by-step process narration.
- Distinguish what the USER stated (confirmed) from what YOU inferred (inferred). Never promote an inference.
- One fact per record. Keep content under 800 characters, but preserve concrete identifiers (module names, commands, error strings).
- Do not repeat anything listed under "Existing memory"."""

    /** Tolerant parser: models wrap JSON in prose/fences; a bad reply must never break the turn. */
    fun parse(raw: String): List<ParsedMemory> {
        val body = findJsonObject(raw) ?: return emptyList()
        val memories = runCatching { json.parseToJsonElement(body).jsonObject["memories"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<ParsedMemory>()
        for (element in memories) {
            val m = runCatching { element.jsonObject }.getOrNull() ?: continue
            val content = m.str("content").orEmpty().trim()
            if (content.isEmpty() || content.length > 1200) continue
            if (MemoryRules.looksLikeSecret(content)) continue
            if (isNoiseMemory(content)) continue
            val why = m.str("why").orEmpty().trim()
            if (MemoryRules.looksLikeSecret(why)) continue
            val type = m.str("type").orEmpty().takeIf { it in MemoryWire.TYPES } ?: "project_fact"
            val scope = if (m.str("scope") == "global" || type == "user_fact") "global" else "project"
            val status = m.str("status").orEmpty().takeIf { it in listOf("confirmed", "inferred", "pending") } ?: "inferred"
            val layer = if (scope == "global") {
                "global"
            } else when (type) {
                "task_result", "success_experience", "failure_experience" -> "episodic"
                "reusable_knowledge", "decision_reason" -> "semantic"
                else -> "project"
            }
            out += ParsedMemory(
                title = m.str("title").orEmpty().trim().take(160).ifBlank { content.take(60) },
                content = content,
                type = type,
                layer = layer,
                scope = scope,
                tags = runCatching {
                    m["tags"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                }.getOrNull().orEmpty().take(8),
                importance = clamp(m["importance"], 0.5),
                confidence = clamp(m["confidence"], if (status == "confirmed") 0.95 else 0.65),
                status = status,
                why = why.takeIf { it.isNotEmpty() && it.length < 600 },
                outcome = m.str("outcome").takeIf { it == "success" || it == "failure" },
            )
        }
        return out
    }

    fun buildRequest(
        messages: List<ExchangeMessage>,
        known: List<String>,
        signals: List<ValueSignal>,
        projectName: String?,
        taskText: String?,
    ): Pair<String, String> {
        val transcript = messages.takeLast(12).joinToString("\n\n") { message ->
            val who = when (message.role) {
                "assistant" -> "Assistant"
                "user" -> "User"
                else -> "Tool"
            }
            "$who: ${message.content.take(2500)}"
        }
        val user = buildString {
            appendLine("Value nodes detected: ${signals.joinToString(", ") { it.kind }.ifBlank { "none" }}")
            if (projectName != null) appendLine("Current project: $projectName") else appendLine("No project open")
            if (taskText != null) appendLine("Task: ${taskText.take(400)}")
            appendLine("Existing memory (do not repeat):")
            if (known.isEmpty()) appendLine("(empty)") else known.forEach { appendLine("- ${it.take(240)}") }
            appendLine("---")
            append("Exchange:\n$transcript")
        }
        return EXTRACTION_PROMPT to user
    }

    private fun findJsonObject(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
        val body = fence?.groupValues?.get(1)?.trim() ?: text
        val start = body.indexOf('{')
        if (start < 0) return null
        var end = body.lastIndexOf('}')
        while (end > start) {
            val candidate = body.substring(start, end + 1)
            if (runCatching { json.parseToJsonElement(candidate) }.isSuccess) return candidate
            end = body.lastIndexOf('}', end - 1)
        }
        return null
    }

    private fun clamp(value: kotlinx.serialization.json.JsonElement?, fallback: Double): Double {
        val number = runCatching { value?.jsonPrimitive?.contentOrNull?.toDouble() }.getOrNull() ?: return fallback
        if (number.isNaN() || number.isInfinite()) return fallback
        return number.coerceIn(0.0, 1.0)
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
