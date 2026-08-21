package com.cone.agent.code

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cone.agent.data.repository.MemoryMatcher
import com.cone.agent.data.repository.MemoryPolicy
import com.cone.agent.data.repository.MemoryWrite
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.workflowStore by preferencesDataStore(name = "cone_code_memory")

/** One durable habit of the user's, e.g. 「不用框架，原生 JS 就好」. */
@Serializable
data class WorkflowMemory(
    val id: Long,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

/**
 * 工作流记忆：how *this user* likes code written, remembered across every project.
 *
 * The companion to the per-project CONE.md, and the split between them is the whole point. A project
 * file is the right home for 「这个项目用 Vue 3 + Pinia」 — it belongs to the code and travels with it.
 * It is the wrong home for 「我不喜欢框架」 or 「注释写中文」, which are true of the user in every
 * project they will ever start, and which they should not have to write into each new folder.
 *
 * Kept in its own DataStore, separate from 问答记忆: mixing them would put a dietary restriction in
 * front of the coding agent and an indentation preference in front of the Q&A one, and each store
 * would spend the other's prompt budget. What they do share is the machinery that matters —
 * [MemoryMatcher] for recognising a restatement and [MemoryPolicy] for refusing secrets.
 */
@Singleton
class CodeMemoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.workflowStore
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(WorkflowMemory.serializer())

    /** Master switch; off means nothing is recalled and nothing new is written. */
    val enabled: Flow<Boolean> = store.data.map { it[KEY_ENABLED] ?: true }

    val memories: Flow<List<WorkflowMemory>> = store.data.map { decode(it[KEY_ITEMS]) }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[KEY_ENABLED] = value }
    }

    /**
     * Saves one habit. Same containment-based duplicate handling as 问答记忆: a restatement is
     * dropped, and a version carrying more detail rewrites the vaguer one instead of sitting beside
     * it — the agent will otherwise re-save a near-identical preference every few sessions.
     */
    suspend fun add(content: String): MemoryWrite {
        val fact = content.trim().trimEnd('。', '.', '；', ';')
        if (fact.length < MIN_CHARS || fact.length > MAX_CHARS) return MemoryWrite.REJECTED
        if (MemoryPolicy.isSensitive(fact)) return MemoryWrite.REJECTED
        if (isProjectScoped(fact)) return MemoryWrite.REJECTED
        if (!enabledNow()) return MemoryWrite.REJECTED

        var result = MemoryWrite.ADDED
        store.edit { prefs ->
            val items = decode(prefs[KEY_ITEMS])
            if (items.any { it.content == fact }) {
                result = MemoryWrite.DUPLICATE
                return@edit
            }
            val now = System.currentTimeMillis()
            val near = items.firstOrNull { MemoryMatcher.containment(it.content, fact) >= NEAR_DUPLICATE }
            if (near != null) {
                if (fact.length > near.content.length) {
                    result = MemoryWrite.REFINED
                    prefs[KEY_ITEMS] = encode(
                        items.map { if (it.id == near.id) it.copy(content = fact, updatedAt = now) else it },
                    )
                } else {
                    result = MemoryWrite.DUPLICATE
                }
                return@edit
            }
            val id = maxOf(now, (items.maxOfOrNull { it.id } ?: 0L) + 1)
            // Oldest first when full: unlike a personal profile there is no "identity" tier here,
            // and a habit that has not been restated in fifty projects has most likely changed.
            val kept = (items + WorkflowMemory(id, fact, now)).takeLast(MAX_ITEMS)
            prefs[KEY_ITEMS] = encode(kept)
        }
        return result
    }

    /**
     * Rejects anything that only holds true inside one project.
     *
     * 「这个项目不用 React」 is the failure that matters: stored as a standing habit it silently
     * teaches the agent to avoid React in *every* future project, including the next one that needs
     * it. A statement that cannot survive being carried into a brand-new empty folder is a project
     * convention, and its home is that project's CONE.md.
     *
     * The prompt already says this at length; this is the backstop for when the model does it
     * anyway, which — being a judgement call made mid-task — it eventually will.
     */
    fun isProjectScoped(text: String): Boolean =
        PROJECT_SCOPED.any { text.contains(it, ignoreCase = true) }

    suspend fun remove(id: Long) {
        store.edit { prefs ->
            prefs[KEY_ITEMS] = encode(decode(prefs[KEY_ITEMS]).filterNot { it.id == id })
        }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY_ITEMS) }
    }

    /** The block injected into the coding agent's system prompt; empty when off or empty. */
    suspend fun promptSection(): String {
        if (!enabledNow()) return ""
        val items = currentItems()
        if (items.isEmpty()) return ""
        return buildString {
            append("\n\n【工作习惯】用户在所有项目里一贯的偏好，除非本次任务或项目约定另有要求，都照此执行：\n")
            items.takeLast(MAX_IN_PROMPT).forEach { appendLine("- ${it.content}") }
        }.trimEnd()
    }

    private suspend fun enabledNow(): Boolean = enabled.first()

    private suspend fun currentItems(): List<WorkflowMemory> = memories.first()

    private fun decode(raw: String?): List<WorkflowMemory> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            .orEmpty()

    private fun encode(items: List<WorkflowMemory>): String = json.encodeToString(listSerializer, items)

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("workflow_memory_enabled")
        val KEY_ITEMS = stringPreferencesKey("workflow_memory_items")

        const val MAX_ITEMS = 40
        const val MAX_IN_PROMPT = 25
        const val MIN_CHARS = 2
        const val MAX_CHARS = 80
        const val NEAR_DUPLICATE = 0.8

        /**
         * Phrases that pin a statement to one project. Matched literally and kept narrow: plain
         * 「项目」 appears in perfectly good habits ("每个项目都先写 README"), so only the
         * demonstratives that point at *this* one disqualify a memory.
         */
        val PROJECT_SCOPED = listOf(
            "这个项目", "这个仓库", "这个应用", "本项目", "本仓库", "当前项目", "此项目", "该项目",
            "这次", "本次", "这个页面里", "这里不用", "这里用",
            "this project", "this repo", "this app", "in this codebase", "for this one",
        )
    }
}
