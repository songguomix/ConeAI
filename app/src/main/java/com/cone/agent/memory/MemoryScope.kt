package com.cone.agent.memory

/**
 * Scope keys for dream memory.
 *
 * Every key is lower-cased and reduced to `[a-z0-9_-]`, so a scope can never become a path
 * segment that escapes the memory directory, and two different projects can never share a key by
 * accident (the separators are normalised the same way on every platform).
 */
object MemoryScope {

    const val GLOBAL = "global"

    fun safeScope(scope: String): String {
        val cleaned = scope.lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(48)
        return cleaned.ifBlank { "default" }
    }

    /** One project, one bucket: notes never leak between projects. */
    fun of(projectId: String): String = "code-" + safeScope(projectId)
}
