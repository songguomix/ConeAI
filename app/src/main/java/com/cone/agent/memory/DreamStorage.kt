package com.cone.agent.memory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-backed dream storage, mirroring ConeCode's object keys:
 * `dream/<scope>/wiki/<slug>.md`, `index.json`, `state.json`, `reports/<at>.json`,
 * `history/<slug>-<at>.md`.
 */
@Singleton
class DreamStorage @Inject constructor(@ApplicationContext private val context: Context) {

    private fun root(scope: String): File =
        File(context.filesDir, "memory/dream/${MemoryScope.safeScope(scope)}")

    fun wikiFile(scope: String, slug: String): File = File(root(scope), "wiki/$slug.md")

    fun indexFile(scope: String): File = File(root(scope), "index.json")

    fun indexMarkdownFile(scope: String): File = File(root(scope), "_index.md")

    fun stateFile(scope: String): File = File(root(scope), "state.json")

    fun reportFile(scope: String, at: Long): File = File(root(scope), "reports/$at.json")

    fun historyFile(scope: String, slug: String, at: Long): File = File(root(scope), "history/$slug-$at.md")

    suspend fun read(file: File): String? = withContext(Dispatchers.IO) {
        if (!file.isFile) null else runCatching { file.readText() }.getOrNull()
    }

    suspend fun write(file: File, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
            true
        }.getOrDefault(false)
    }

    suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    }

    suspend fun listWiki(scope: String): List<File> = withContext(Dispatchers.IO) {
        File(root(scope), "wiki").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    suspend fun clear(scope: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { root(scope).deleteRecursively() }.getOrDefault(false)
    }
}
