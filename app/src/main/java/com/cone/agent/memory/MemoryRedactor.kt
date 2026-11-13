package com.cone.agent.memory

/**
 * Credential redaction, ported from ConeCode's dream wiki.
 *
 * Dream notes are written from whole session transcripts, and a transcript is exactly where a
 * pasted key ends up. Redaction runs on the way in (before storage) and on the way out (before a
 * note is mounted into a prompt), so a value that slipped through an older build is still caught.
 */
object MemoryRedactor {

    private val PATTERNS: List<Regex> = listOf(
        // Private key blocks, however they are wrapped.
        Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
        Regex("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
        Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
        // key: value / key=value pairs whose value is long enough to be a credential.
        Regex(
            "((?:password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\s*[:=]\\s*)([\"']?)[^\\s\"',;]{6,}\\2",
            RegexOption.IGNORE_CASE,
        ),
    )

    fun redact(text: String): String {
        var out = text
        for (pattern in PATTERNS) {
            out = pattern.replace(out) { match ->
                val prefix = match.groupValues.getOrNull(1).orEmpty()
                prefix + "[redacted]"
            }
        }
        return out
    }
}
