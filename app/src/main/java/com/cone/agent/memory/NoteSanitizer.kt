package com.cone.agent.memory

/**
 * Defuses instruction-shaped content in a dream note, ported from ConeCode's dream wiki fix.
 *
 * Dream notes are distilled from untrusted session content (pasted text, fetched pages, repo
 * files). A note that carries role markers or override phrases is not a fact — it is an
 * injection attempt. The scaffolds are replaced with a visible marker so the surrounding facts
 * survive, and the pass runs both when a note is stored and when it is mounted into a prompt.
 */
object NoteSanitizer {

    private val SCAFFOLDS: List<Regex> = listOf(
        Regex("<\\|\\s*(?:im_start|im_end|endoftext|system|assistant|user)\\s*\\|>", RegexOption.IGNORE_CASE),
        Regex("</?(?:system|assistant|developer|tool_call)\\b[^>]*>", RegexOption.IGNORE_CASE),
        Regex("\\[INST]|\\[/INST]|<<SYS>>|<</SYS>>"),
        Regex("^\\s*(?:system|assistant|developer)\\s*:\\s*", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
        Regex(
            "(?:ignore|disregard|forget|override)\\s+(?:all\\s+)?(?:the\\s+)?" +
                "(?:previous|prior|above|earlier|system)\\s+(?:instructions?|prompts?|rules?)",
            RegexOption.IGNORE_CASE,
        ),
        Regex("you\\s+are\\s+now\\s+(?:a|an|the)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bfrom\\s+now\\s+on,?\\s+you\\s+(?:must|will|should)\\b", RegexOption.IGNORE_CASE),
        Regex("```json[\\s\\S]*?\"action\"[\\s\\S]*?```"),
        Regex("<tool_call>[\\s\\S]*?</tool_call>"),
        Regex("<<<|>>>"),
    )

    private val INVISIBLE = Regex(
        "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]",
    )

    private const val MARKER = "〔已移除可疑指令〕"

    fun sanitize(text: String, max: Int = 4000): String {
        var out = text.replace(INVISIBLE, "")
        for (pattern in SCAFFOLDS) out = pattern.replace(out, MARKER)
        return MemoryRedactor.redact(out).trim().take(max)
    }
}
