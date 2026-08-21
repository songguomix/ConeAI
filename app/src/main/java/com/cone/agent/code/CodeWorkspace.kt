package com.cone.agent.code

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sandboxed file system behind 「写代码」.
 *
 * Every project is a directory under the root [CodeStorage] resolves — by default a `ConeAI` folder
 * on shared storage — and **every** path the model supplies is resolved through [resolve], which
 * canonicalizes first and then refuses anything landing outside that project.
 *
 * That check matters more now than it did in app-private storage: the model writes paths, so
 * `../../DCIM/Camera` is a request that will arrive sooner or later, from a confused model or from
 * text the user pasted in, and with all-files access granted it would otherwise land on the user's
 * real photos rather than bouncing off the sandbox.
 */
@Singleton
class CodeWorkspace @Inject constructor(
    private val storage: CodeStorage,
    private val json: Json,
) {

    private val root: File get() = storage.root.value.apply { mkdirs() }

    private val _projects = MutableStateFlow<List<CodeProject>>(emptyList())
    val projects: StateFlow<List<CodeProject>> = _projects.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _projects.value = root.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { readMeta(it) }
            .sortedByDescending { it.updatedAt }
    }

    /* ---------------- projects ---------------- */

    /**
     * Creates the project's own folder and, when [seed] is set, the template's starter files.
     *
     * A project the AI is about to build starts empty on purpose: it is going to write the real
     * files in a moment, and a leftover placeholder README would just be litter in a folder the
     * user now browses in a file manager.
     */
    suspend fun createProject(
        name: String,
        template: ProjectTemplate,
        seed: Boolean = true,
    ): CodeProject =
        withContext(Dispatchers.IO) {
            val clean = name.trim().ifBlank { "新项目" }
            val dir = File(root, uniqueId(clean)).apply { mkdirs() }
            val now = System.currentTimeMillis()
            if (seed) {
                template.files.forEach { (path, content) ->
                    File(dir, path).apply { parentFile?.mkdirs() }.writeText(content)
                }
            }
            writeMeta(dir, clean, template.id, now, now)
            refresh()
            readMeta(dir) ?: CodeProject(dir.name, clean, template.id, now, now)
        }

    suspend fun renameProject(project: CodeProject, name: String) = withContext(Dispatchers.IO) {
        val dir = dirOf(project)
        val clean = name.trim().ifBlank { return@withContext }
        writeMeta(dir, clean, project.template, project.createdAt, System.currentTimeMillis())
        refresh()
    }

    suspend fun deleteProject(project: CodeProject) = withContext(Dispatchers.IO) {
        dirOf(project).deleteRecursively()
        refresh()
    }

    fun dirOf(project: CodeProject): File = File(root, project.id)

    /* ---------------- tree ---------------- */

    /**
     * Flattened, depth-first file tree. Only this feature's own bookkeeping is hidden — a project's
     * real dotfiles (`.gitignore`, `.env`) are part of the project and stay visible to both the user
     * and the model.
     */
    suspend fun tree(project: CodeProject): List<CodeNode> = withContext(Dispatchers.IO) {
        val out = ArrayList<CodeNode>()
        walk(dirOf(project), dirOf(project), 0, out)
        out
    }

    private fun walk(base: File, dir: File, depth: Int, out: MutableList<CodeNode>) {
        if (out.size > MAX_TREE_ENTRIES || depth > MAX_DEPTH) return
        val children = dir.listFiles().orEmpty()
            .filterNot { it.name in RESERVED }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        for (child in children) {
            val rel = child.relativeTo(base).path
            out += CodeNode(rel, child.name, child.isDirectory, child.length(), depth)
            if (child.isDirectory) walk(base, child, depth + 1, out)
        }
    }

    /** The tree as an indented text block, for the model's context. */
    suspend fun treeText(project: CodeProject): String {
        val nodes = tree(project)
        if (nodes.isEmpty()) return "（空项目，还没有任何文件）"
        return nodes.joinToString("\n") { node ->
            val indent = "  ".repeat(node.depth)
            if (node.isDir) "$indent${node.name}/" else "$indent${node.name}  (${node.size} B)"
        }
    }

    /* ---------------- files ---------------- */

    suspend fun read(project: CodeProject, path: String): Result<String> = withContext(Dispatchers.IO) {
        val file = resolve(project, path) ?: return@withContext Result.failure(reject(path))
        when {
            !file.exists() -> Result.failure(IllegalArgumentException("文件不存在：$path"))
            file.isDirectory -> Result.failure(IllegalArgumentException("这是一个目录，不是文件：$path"))
            file.length() > MAX_FILE_BYTES ->
                Result.failure(IllegalArgumentException("文件过大（${file.length()} 字节），无法打开"))
            else -> runCatching { file.readText() }
        }
    }

    suspend fun write(project: CodeProject, path: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val file = resolve(project, path) ?: return@withContext Result.failure(reject(path))
            if (file.isDirectory) {
                return@withContext Result.failure(IllegalArgumentException("这是一个目录，不能写入：$path"))
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(content)
                touch(project)
            }
        }

    suspend fun delete(project: CodeProject, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val file = resolve(project, path) ?: return@withContext Result.failure(reject(path))
        // Refuse the project root itself: "delete ." must not wipe the project from under the user.
        if (file.canonicalPath == dirOf(project).canonicalPath) {
            return@withContext Result.failure(IllegalArgumentException("不能删除项目根目录"))
        }
        if (!file.exists()) return@withContext Result.failure(IllegalArgumentException("文件不存在：$path"))
        runCatching {
            if (!file.deleteRecursively()) error("删除失败：$path")
            touch(project)
        }
    }

    suspend fun rename(project: CodeProject, from: String, to: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val src = resolve(project, from) ?: return@withContext Result.failure(reject(from))
            val dst = resolve(project, to) ?: return@withContext Result.failure(reject(to))
            if (!src.exists()) return@withContext Result.failure(IllegalArgumentException("文件不存在：$from"))
            runCatching {
                dst.parentFile?.mkdirs()
                if (!src.renameTo(dst)) error("重命名失败：$from → $to")
                touch(project)
            }
        }

    fun exists(project: CodeProject, path: String): Boolean =
        resolve(project, path)?.exists() == true

    /**
     * Grep across the project, returning `path:line: text` hits. [regex] switches from a plain
     * case-insensitive substring to a full regular expression; a pattern that does not compile is
     * reported rather than silently searched for literally.
     */
    suspend fun search(
        project: CodeProject,
        query: String,
        regex: Boolean = false,
        glob: String = "",
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())
        val pattern = if (regex) {
            runCatching { Regex(query, RegexOption.IGNORE_CASE) }
                .getOrElse { return@withContext Result.failure(IllegalArgumentException("正则有误：${it.message}")) }
        } else {
            null
        }
        val filter = globRegex(glob)
        val hits = ArrayList<String>()
        for (node in tree(project)) {
            if (node.isDir) continue
            if (hits.size >= MAX_SEARCH_HITS) break
            if (filter != null && !filter.matches(node.path)) continue
            val file = resolve(project, node.path) ?: continue
            if (file.length() > MAX_FILE_BYTES) continue
            runCatching { file.readLines() }.getOrNull()?.forEachIndexed { index, line ->
                val match = if (pattern != null) pattern.containsMatchIn(line) else line.contains(query, ignoreCase = true)
                if (hits.size < MAX_SEARCH_HITS && match) {
                    hits += "${node.path}:${index + 1}: ${line.trim().take(160)}"
                }
            }
        }
        Result.success(hits)
    }

    /** Paths matching a shell-style glob (`src/**/*.js`). */
    suspend fun glob(project: CodeProject, pattern: String): List<String> = withContext(Dispatchers.IO) {
        val regex = globRegex(pattern) ?: return@withContext emptyList()
        tree(project).filter { !it.isDir && regex.matches(it.path) }.map { it.path }.take(MAX_SEARCH_HITS)
    }

    /**
     * Glob → regex. `**` crosses directory separators, `*` and `?` do not — the usual convention, and
     * the one the model will assume.
     */
    private fun globRegex(pattern: String): Regex? {
        val clean = pattern.trim().removePrefix("./").trim('/')
        if (clean.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                c == '*' && i + 1 < clean.length && clean[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    if (i < clean.length && clean[i] == '/') i++
                }
                c == '*' -> { sb.append("[^/]*"); i++ }
                c == '?' -> { sb.append("[^/]"); i++ }
                c in ".()+|^$@%{}[]\\" -> { sb.append('\\').append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return runCatching { Regex(sb.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
    }

    /**
     * The project's own instructions file, the equivalent of a CLAUDE.md: prepended to every system
     * prompt so conventions the user wrote down once are followed on every later turn.
     */
    suspend fun instructions(project: CodeProject): String = withContext(Dispatchers.IO) {
        val file = File(dirOf(project), INSTRUCTIONS_NAME)
        if (!file.isFile || file.length() > MAX_INSTRUCTIONS_BYTES) return@withContext ""
        runCatching { file.readText().trim() }.getOrDefault("")
    }

    fun instructionsName(): String = INSTRUCTIONS_NAME

    /* ---------------- checkpoints ---------------- */

    /**
     * Copies the project's files aside before the agent touches them, so a turn that goes wrong can
     * be taken back. Snapshots are plain directory copies — for hand-sized projects that costs
     * milliseconds, and it means a restore is a copy back rather than a patch replay that could
     * itself be buggy.
     */
    suspend fun checkpoint(project: CodeProject): String? = withContext(Dispatchers.IO) {
        val dir = dirOf(project)
        val id = System.currentTimeMillis().toString()
        val target = File(dir, "$CHECKPOINT_DIR/$id")
        val ok = runCatching {
            target.mkdirs()
            copyTree(dir, target)
            true
        }.getOrDefault(false)
        if (!ok) return@withContext null
        // Keep the tail only: older snapshots are rarely wanted and the copies add up.
        File(dir, CHECKPOINT_DIR).listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .dropLast(MAX_CHECKPOINTS)
            .forEach { it.deleteRecursively() }
        id
    }

    suspend fun restore(project: CodeProject, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = dirOf(project)
        val source = File(dir, "$CHECKPOINT_DIR/$id")
        if (!source.isDirectory) {
            return@withContext Result.failure(IllegalArgumentException("找不到这个检查点"))
        }
        runCatching {
            dir.listFiles().orEmpty().filterNot { it.name in RESERVED }.forEach { it.deleteRecursively() }
            copyTree(source, dir)
            touch(project)
        }
    }

    fun hasCheckpoint(project: CodeProject, id: String): Boolean =
        File(dirOf(project), "$CHECKPOINT_DIR/$id").isDirectory

    private fun copyTree(from: File, to: File, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        from.listFiles().orEmpty().filterNot { it.name in RESERVED }.forEach { child ->
            val dest = File(to, child.name)
            if (child.isDirectory) {
                dest.mkdirs()
                copyTree(child, dest, depth + 1)
            } else {
                runCatching { child.copyTo(dest, overwrite = true) }
            }
        }
    }

    /** The entry point a WebView preview should load, or null when the project has no page to show. */
    suspend fun previewTarget(project: CodeProject): File? = withContext(Dispatchers.IO) {
        val dir = dirOf(project)
        listOf("index.html", "index.htm").firstNotNullOfOrNull { name ->
            File(dir, name).takeIf { it.isFile }
        } ?: tree(project).firstOrNull { !it.isDir && CodeLang.of(it.path) == CodeLang.HTML }
            ?.let { File(dir, it.path) }
    }

    /* ---------------- transcript ---------------- */

    suspend fun loadTranscript(project: CodeProject): List<CodeTurn> = withContext(Dispatchers.IO) {
        val file = File(dirOf(project), SESSION_PATH)
        if (!file.isFile) return@withContext emptyList()
        runCatching { json.decodeFromString<List<CodeTurn>>(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun saveTranscript(project: CodeProject, turns: List<CodeTurn>) = withContext(Dispatchers.IO) {
        val file = File(dirOf(project), SESSION_PATH)
        runCatching {
            file.parentFile?.mkdirs()
            // Trailing streaming flags would replay as a stuck "thinking" bubble on reopen.
            file.writeText(json.encodeToString(turns.takeLast(MAX_SAVED_TURNS).map { it.copy(streaming = false) }))
        }
        Unit
    }

    /* ---------------- internals ---------------- */

    /**
     * Maps a model-supplied relative path onto a real file, or null when it escapes the project.
     * Canonicalization happens *before* the comparison so `a/../../b`, a symlink, and `./x` are all
     * judged by where they actually land rather than by how they were spelled.
     */
    private fun resolve(project: CodeProject, path: String): File? {
        val cleaned = path.trim().removePrefix("./").trim('/')
        val dir = dirOf(project)
        val target = runCatching { File(dir, cleaned).canonicalFile }.getOrNull() ?: return null
        val base = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val basePath = base.path
        val contained = target.path == basePath || target.path.startsWith("$basePath${File.separator}")
        if (!contained) return null
        // The workspace's own bookkeeping is not part of the project: a model rewriting
        // .coneproject would rename the project out from under the user, and .cone/session.json
        // is the transcript being written *while* the model runs.
        val relative = target.path.removePrefix(basePath).trim(File.separatorChar)
        if (relative.split(File.separatorChar).any { it in RESERVED }) return null
        return target
    }

    private fun reject(path: String) =
        IllegalArgumentException("路径超出了项目范围，已拒绝：$path")

    private fun touch(project: CodeProject) {
        val dir = dirOf(project)
        val meta = readMeta(dir) ?: return
        writeMeta(dir, meta.name, meta.template, meta.createdAt, System.currentTimeMillis())
    }

    private fun readMeta(dir: File): CodeProject? {
        val file = File(dir, META_NAME)
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull()
        val now = System.currentTimeMillis()
        return CodeProject(
            id = dir.name,
            name = obj?.optString("name")?.ifBlank { null } ?: dir.name,
            template = obj?.optString("template")?.ifBlank { null } ?: ProjectTemplate.BLANK.id,
            createdAt = obj?.optLong("createdAt")?.takeIf { it > 0 } ?: dir.lastModified().takeIf { it > 0 } ?: now,
            updatedAt = obj?.optLong("updatedAt")?.takeIf { it > 0 } ?: dir.lastModified().takeIf { it > 0 } ?: now,
            fileCount = countFiles(dir, 0),
        )
    }

    private fun countFiles(dir: File, depth: Int): Int {
        if (depth > MAX_DEPTH) return 0
        return dir.listFiles().orEmpty()
            .filterNot { it.name in RESERVED }
            .sumOf { if (it.isDirectory) countFiles(it, depth + 1) else 1 }
    }

    private fun writeMeta(dir: File, name: String, template: String, createdAt: Long, updatedAt: Long) {
        runCatching {
            File(dir, META_NAME).writeText(
                JSONObject()
                    .put("name", name)
                    .put("template", template)
                    .put("createdAt", createdAt)
                    .put("updatedAt", updatedAt)
                    .toString(),
            )
        }
    }

    /**
     * The on-disk folder name. Now that projects live in shared storage the user browses, the folder
     * keeps the project's real name — including Chinese — instead of an ASCII slug; only characters
     * that are genuinely illegal in a file name are replaced.
     */
    private fun uniqueId(name: String): String {
        val slug = name.trim()
            .map { if (it in ILLEGAL_NAME_CHARS || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim('.', ' ')
            .take(48)
            .ifBlank { "project" }
        var candidate = slug
        var n = 2
        while (File(root, candidate).exists()) {
            candidate = "$slug-$n"
            n++
        }
        return candidate
    }

    private companion object {
        const val META_NAME = ".coneproject"
        const val SESSION_DIR = ".cone"
        const val SESSION_PATH = "$SESSION_DIR/session.json"

        /** Names the workspace owns; never listed, never reachable through [resolve]. */
        val RESERVED = setOf(META_NAME, SESSION_DIR)
        const val MAX_TREE_ENTRIES = 400
        const val MAX_DEPTH = 8
        const val MAX_FILE_BYTES = 512 * 1024L
        const val MAX_SEARCH_HITS = 60
        const val MAX_SAVED_TURNS = 120
        const val INSTRUCTIONS_NAME = "CONE.md"
        const val CHECKPOINT_DIR = "$SESSION_DIR/checkpoints"
        const val MAX_CHECKPOINTS = 8
        const val MAX_INSTRUCTIONS_BYTES = 24 * 1024L

        /** Reserved by FAT/exFAT (SD cards, USB-OTG) as well as by Linux; replaced with "_". */
        val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    }
}
