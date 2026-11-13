package com.cone.agent.code

import android.content.Context
import android.os.Build
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.remote.ChatChunk
import com.cone.agent.data.remote.LlmClient
import com.cone.agent.data.remote.WebSearchClient
import com.cone.agent.data.remote.dto.ChatMessageDto
import com.cone.agent.data.remote.dto.ContentPart
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.memory.DreamRunner
import com.cone.agent.memory.MemoryScope
import com.cone.agent.memory.records.MemoryService
import com.cone.agent.memory.records.RememberOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** What the coding loop reports back to the UI as it runs. */
sealed interface CodeEvent {
    /** A fresh assistant turn began (one per model round). */
    data object RoundStart : CodeEvent

    /** The current round's raw text so far — replaces, not appends. */
    data class Text(val full: String) : CodeEvent

    /** A tool finished; outcomes arrive in the same order as the calls in the round's text. */
    data class Tool(val outcome: ToolOutcome) : CodeEvent

    /** The agent stopped to ask the user something; the run ends until they reply. */
    data class Asked(val question: String, val tokens: Int?) : CodeEvent

    /** A plan is on the table and needs approval before anything is written. */
    data class Planned(val tokens: Int?) : CodeEvent

    data class Finished(val tokens: Int?) : CodeEvent
    data class Failed(val message: String) : CodeEvent
}

/**
 * The coding brain behind 「写代码」 — a port of ConeCode's agent loop onto the phone workspace.
 *
 * It keeps ConeCode's shape: a JSON action protocol (no native function-calling needed, so any
 * OpenAI-/Anthropic-/Gemini-compatible endpoint works), a single tool catalog that the system
 * prompt and the executor both read, and an UNDERSTAND → PLAN → ACT → VERIFY → REVIEW loop that
 * ends when the model stops issuing actions.
 */
@Singleton
class CodeAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmClient: LlmClient,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val workspace: CodeWorkspace,
    private val memoryService: MemoryService,
    private val webSearchClient: WebSearchClient,
    private val httpClient: OkHttpClient,
    private val dream: DreamRunner,
) {

    fun run(
        project: CodeProject,
        task: String,
        history: List<CodeTurn>,
        planMode: Boolean = false,
    ): Flow<CodeEvent> = flow {
        val selected = settingsRepository.chatModel.first() ?: settingsRepository.selectedModel.first()
        if (selected == null) {
            emit(CodeEvent.Failed(LocaleHelper.string(context, R.string.code_err_no_model)))
            return@flow
        }
        val provider = providerRepository.resolve(selected.providerId)
        if (provider == null) {
            emit(CodeEvent.Failed(LocaleHelper.string(context, R.string.code_err_no_provider)))
            return@flow
        }

        val messages = ArrayList<ChatMessageDto>()
        // The project's own CONE.md, if it has one: conventions the user wrote down once and should
        // not have to repeat every turn.
        val instructions = workspace.instructions(project)
        // Two memories, deliberately separate. CONE.md belongs to the project and travels with the
        // code; 工作习惯 belongs to the user and follows them into every project they ever start.
        messages += system(
            systemPrompt(selected.modelId, project) + instructionsSection(instructions) +
                planSection(planMode),
        )
        // Durable facts and the dream wiki both ride the USER channel: they are quoted data
        // distilled from past sessions, not operator instructions.
        val memoryBlock = memoryService.retrieveBlock(task, projectId = null, limit = 6)
        if (memoryBlock.isNotBlank()) messages += user(memoryBlock)
        // Dream notes ride the USER channel: they are quoted data distilled from untrusted
        // sessions, so they must not sit next to the system prompt's authority.
        val dreamBlock = dream.context(MemoryScope.of(project.id), task)
        if (dreamBlock.isNotBlank()) messages += user(dreamBlock)
        history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
            when (turn.role) {
                CodeRole.USER -> messages += user(turn.text)
                CodeRole.ASSISTANT -> messages += assistant(compact(turn.text))
                CodeRole.ERROR, CodeRole.NOTE -> Unit
            }
        }
        messages += user("项目「${project.name}」当前文件：\n${workspace.treeText(project)}\n\n任务：$task")

        var tokens: Int? = null
        var round = 0
        while (round < MAX_ROUNDS) {
            round++
            emit(CodeEvent.RoundStart)
            val buffer = StringBuilder()
            var roundTokens: Int? = null
            try {
                llmClient.chatStream(
                    baseUrl = provider.baseUrl,
                    apiKey = provider.apiKey,
                    model = selected.modelId,
                    messages = messages,
                    protocol = provider.protocol,
                    temperature = TEMPERATURE,
                    maxTokens = MAX_TOKENS,
                ).collect { chunk ->
                    when (chunk) {
                        is ChatChunk.Delta -> {
                            buffer.append(chunk.text)
                            emit(CodeEvent.Text(buffer.toString()))
                        }
                        is ChatChunk.Done -> roundTokens = chunk.totalTokens
                    }
                }
            } catch (e: Exception) {
                emit(CodeEvent.Failed(e.message ?: LocaleHelper.string(context, R.string.code_err_request)))
                return@flow
            }
            tokens = sum(tokens, roundTokens)

            val reply = buffer.toString()
            val calls = CodeProtocol.calls(reply)
            if (calls.isEmpty()) {
                // ConeCode's closing rule: a plain-text report with no action block ends the run.
                emit(CodeEvent.Finished(tokens))
                return@flow
            }

            val results = StringBuilder()
            var stop: CodeEvent? = null
            for (call in calls) {
                when {
                    call.tool == "done" -> stop = CodeEvent.Finished(tokens)
                    // A question ends the round: the answer is the user's next message, and the
                    // history carries the question forward so the next run just continues.
                    call.tool == "ask_user" -> {
                        val question = call.content.ifBlank { call.question }.ifBlank { call.query }.trim()
                        val shown = if (call.options.isNotEmpty()) {
                            "$question\n（可选：${call.options.joinToString(" / ")}）"
                        } else question
                        emit(CodeEvent.Tool(ToolOutcome("ask_user", question, true, "等待你的回答")))
                        stop = CodeEvent.Asked(shown, tokens)
                    }
                    call.tool == "plan" -> {
                        emit(CodeEvent.Tool(ToolOutcome("plan", "", true, "等待你确认")))
                        stop = CodeEvent.Planned(tokens)
                    }
                    // Planning is planning. The prompt says not to write, but a model that writes
                    // anyway must not be the reason files change before the user approved anything.
                    planMode && call.tool in MUTATING_TOOLS -> {
                        val outcome = ToolOutcome(
                            call.tool,
                            call.path,
                            false,
                            "计划模式下不能改文件，请先用 {\"action\": \"plan\"} 交出计划等用户确认",
                        )
                        emit(CodeEvent.Tool(outcome))
                        results.append(feedback(call, outcome)).append("\n\n")
                    }
                    else -> {
                        val outcome = execute(project, call)
                        emit(CodeEvent.Tool(outcome))
                        results.append(feedback(call, outcome)).append("\n\n")
                    }
                }
                if (stop != null) break
            }
            stop?.let {
                emit(it)
                return@flow
            }
            messages += assistant(reply)
            messages += user(
                buildString {
                    append("工具执行结果：\n\n")
                    append(results.toString().trim())
                    append("\n\n请继续。全部完成后停止发出动作，直接用纯文本总结改了什么。")
                },
            )
        }
        emit(CodeEvent.Finished(tokens))
    }

    /**
     * Condenses a long transcript into one paragraph the next turn can start from.
     *
     * Context fills up fast when whole files travel through it, and the alternative to compacting is
     * the model quietly forgetting the earliest — usually most important — instructions.
     */
    suspend fun summarize(turns: List<CodeTurn>): Result<String> {
        val selected = settingsRepository.chatModel.first() ?: settingsRepository.selectedModel.first()
            ?: return Result.failure(IllegalStateException(LocaleHelper.string(context, R.string.code_err_no_model)))
        val provider = providerRepository.resolve(selected.providerId)
            ?: return Result.failure(IllegalStateException(LocaleHelper.string(context, R.string.code_err_no_provider)))

        val transcript = turns.joinToString("\n\n") { turn ->
            val who = when (turn.role) {
                CodeRole.USER -> "用户"
                CodeRole.ASSISTANT -> "助手"
                CodeRole.ERROR -> "错误"
                CodeRole.NOTE -> "系统"
            }
            "$who：" + compact(turn.text).take(2000)
        }.takeLast(MAX_SUMMARY_INPUT)

        return llmClient.chat(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = selected.modelId,
            messages = listOf(
                system(
                    "把下面这段编码会话压缩成一段简明的中文交接说明，供接手的编码助手继续工作。" +
                        "必须保留：用户的目标与明确要求、已经建了哪些文件、每个文件的作用、" +
                        "做过的关键决定、还没完成的事。不要写代码，不要客套。",
                ),
                user(transcript),
            ),
            protocol = provider.protocol,
            temperature = 0.3,
            maxTokens = 1200,
        ).map { it.content }
    }

    /* ---------------- tool execution ---------------- */

    private suspend fun execute(project: CodeProject, call: ToolCall): ToolOutcome = when (call.tool) {
        "read_file" -> read(project, call)
        "list_dir" -> workspace.listDir(project, call.path).fold(
            onSuccess = { listing ->
                ToolOutcome("list_dir", call.path.ifBlank { project.name }, true, "查看目录", listing)
            },
            onFailure = { ToolOutcome("list_dir", call.path, false, it.message.orEmpty()) },
        )
        "search" -> workspace.search(project, call.query, regex = call.regex).fold(
            onSuccess = { hits ->
                ToolOutcome(
                    tool = "search",
                    target = call.query,
                    ok = true,
                    summary = if (hits.isEmpty()) "没有匹配" else "${hits.size} 处匹配",
                    detail = hits.joinToString("\n"),
                )
            },
            onFailure = { ToolOutcome("search", call.query, false, it.message.orEmpty()) },
        )
        "glob" -> {
            val paths = workspace.glob(project, call.query)
            ToolOutcome(
                tool = "glob",
                target = call.query,
                ok = true,
                summary = if (paths.isEmpty()) "没有匹配" else "${paths.size} 个文件",
                detail = paths.joinToString("\n"),
            )
        }
        "create_file" -> write(project, call)
        "edit_file" -> edit(project, call)
        "create_dir" -> workspace.createDir(project, call.path).fold(
            onSuccess = { ToolOutcome("create_dir", call.path, true, "已创建目录") },
            onFailure = { ToolOutcome("create_dir", call.path, false, it.message.orEmpty()) },
        )
        "delete" -> workspace.delete(project, call.path).fold(
            onSuccess = { ToolOutcome("delete", call.path, true, "已删除") },
            onFailure = { ToolOutcome("delete", call.path, false, it.message.orEmpty()) },
        )
        "rename" -> workspace.rename(project, call.path, call.to).fold(
            onSuccess = { ToolOutcome("rename", "${call.path} → ${call.to}", true, "已重命名") },
            onFailure = { ToolOutcome("rename", call.path, false, it.message.orEmpty()) },
        )
        "copy" -> workspace.copy(project, call.path, call.to).fold(
            onSuccess = { ToolOutcome("copy", "${call.path} → ${call.to}", true, "已复制") },
            onFailure = { ToolOutcome("copy", call.path, false, it.message.orEmpty()) },
        )
        "download" -> download(project, call)
        "remember" -> remember(call.content.ifBlank { call.text }.ifBlank { call.query })
        "memory_search" -> memorySearch(call.query, call.limit)
        "update_todos" -> ToolOutcome(
            tool = "update_todos",
            target = "",
            ok = true,
            summary = "${call.todos.size.coerceAtLeast(call.content.lines().count { it.isNotBlank() })} 项",
            detail = call.content,
        )
        "web_search" -> webSearchClient.search(call.query, maxResults = 5).fold(
            onSuccess = { results ->
                ToolOutcome(
                    tool = "web_search",
                    target = call.query,
                    ok = true,
                    summary = "${results.size} 条结果",
                    detail = results.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" },
                )
            },
            onFailure = { ToolOutcome("web_search", call.query, false, it.message ?: "搜索失败") },
        )
        "web_fetch" -> fetch(call.url.ifBlank { call.query })
        "system_info" -> systemInfo(project)
        else -> ToolOutcome(call.tool, call.path, false, "手机端不支持这个工具：${call.tool}")
    }

    /**
     * Stores one standing preference of the user's. Rejected content is reported honestly rather
     * than silently swallowed — the model is told what happened so it does not try again next turn.
     */
    private suspend fun remember(text: String): ToolOutcome {
        val fact = text.trim()
        if (fact.isBlank()) return ToolOutcome("remember", "", false, "remember 需要给出要记住的一句话")
        return when (memoryService.remember(fact)) {
            RememberOutcome.ADDED -> ToolOutcome("remember", fact, true, "已记住")
            RememberOutcome.UPDATED -> ToolOutcome("remember", fact, true, "已更新")
            RememberOutcome.DUPLICATE -> ToolOutcome("remember", fact, true, "已经记住过了")
            RememberOutcome.REJECTED ->
                ToolOutcome("remember", fact, false, "这条不能存入长期记忆（过短、为空，或含敏感信息）")
        }
    }

    private suspend fun memorySearch(query: String, limit: Int): ToolOutcome {
        val hits = memoryService.relevant(query, projectId = null, limit = (if (limit > 0) limit else 5).coerceIn(1, 10))
        return ToolOutcome(
            tool = "memory_search",
            target = query,
            ok = true,
            summary = if (hits.isEmpty()) "没有匹配" else "${hits.size} 条",
            detail = hits.joinToString("\n") { "- ${it.title}：${it.content}" },
        )
    }

    private suspend fun read(project: CodeProject, call: ToolCall): ToolOutcome =
        workspace.read(project, call.path).fold(
            onSuccess = { text ->
                ToolOutcome(
                    tool = "read_file",
                    target = call.path,
                    ok = true,
                    summary = "${text.lines().size} 行",
                    detail = text.take(MAX_DETAIL_CHARS),
                )
            },
            onFailure = { ToolOutcome("read_file", call.path, false, it.message.orEmpty()) },
        )

    private suspend fun write(project: CodeProject, call: ToolCall): ToolOutcome {
        if (call.path.isBlank()) return ToolOutcome("create_file", "", false, "缺少 path")
        val existed = workspace.exists(project, call.path)
        val before = if (existed) workspace.read(project, call.path).getOrDefault("") else ""
        return workspace.write(project, call.path, call.content).fold(
            onSuccess = {
                val (added, removed) = CodeDiff.stats(before, call.content)
                ToolOutcome(
                    tool = "create_file",
                    target = call.path,
                    ok = true,
                    summary = if (existed) "已覆盖" else "已新建",
                    added = added,
                    removed = removed,
                    diff = CodeDiff.compute(before, call.content),
                )
            },
            onFailure = { ToolOutcome("create_file", call.path, false, it.message.orEmpty()) },
        )
    }

    /**
     * Literal, unique string replacements applied in order; `content` (whole rewrite) is the
     * fallback ConeCode's edit_file offers when a precise match is impractical.
     *
     * Both guards matter: a match that appears twice would let the model silently patch the wrong
     * occurrence, and a match that appears zero times usually means the model is editing against a
     * file it only imagined — better to say so and let it read. The whole batch is checked and
     * applied in memory first, so a bad pair later in the list cannot leave the file half-edited.
     */
    private suspend fun edit(project: CodeProject, call: ToolCall): ToolOutcome {
        if (call.path.isBlank()) return ToolOutcome("edit_file", "", false, "缺少 path")
        val before = workspace.read(project, call.path).getOrElse {
            return ToolOutcome("edit_file", call.path, false, it.message.orEmpty())
        }
        if (call.edits.isEmpty()) {
            if (call.content.isBlank()) {
                return ToolOutcome("edit_file", call.path, false, "edit_file 需要 old/new 或 content")
            }
            return workspace.write(project, call.path, call.content).fold(
                onSuccess = {
                    val (added, removed) = CodeDiff.stats(before, call.content)
                    ToolOutcome(
                        tool = "edit_file",
                        target = call.path,
                        ok = true,
                        summary = "已整体重写",
                        added = added,
                        removed = removed,
                        diff = CodeDiff.compute(before, call.content),
                    )
                },
                onFailure = { ToolOutcome("edit_file", call.path, false, it.message.orEmpty()) },
            )
        }
        var after = before
        call.edits.forEachIndexed { index, (old, new) ->
            val label = if (call.edits.size > 1) "第 ${index + 1} 处：" else ""
            if (old.isEmpty()) {
                return ToolOutcome("edit_file", call.path, false, "$label old 为空")
            }
            val occurrences = after.split(old).size - 1
            if (occurrences == 0) {
                return ToolOutcome("edit_file", call.path, false, "${label}文件中找不到 old 的内容，请先 read_file 再改")
            }
            if (occurrences > 1 && !call.replaceAll) {
                return ToolOutcome(
                    "edit_file",
                    call.path,
                    false,
                    "$label old 出现了 $occurrences 次，请提供更长、唯一的片段，或加 replaceAll=true",
                )
            }
            after = if (call.replaceAll) after.replace(old, new) else after.replaceFirst(old, new)
        }
        return workspace.write(project, call.path, after).fold(
            onSuccess = {
                val (added, removed) = CodeDiff.stats(before, after)
                ToolOutcome(
                    tool = "edit_file",
                    target = call.path,
                    ok = true,
                    summary = if (call.edits.size > 1) "已修改 ${call.edits.size} 处" else "已修改",
                    added = added,
                    removed = removed,
                    diff = CodeDiff.compute(before, after),
                )
            },
            onFailure = { ToolOutcome("edit_file", call.path, false, it.message.orEmpty()) },
        )
    }

    /**
     * Downloads a URL straight into the project. ConeCode has the same tool for fetching a library
     * or asset the user asked for; on a phone this is the only way to bring a binary into the
     * sandbox without a terminal.
     */
    private suspend fun download(project: CodeProject, call: ToolCall): ToolOutcome {
        val url = call.url.ifBlank { call.query }.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolOutcome("download", url, false, "只支持 http(s) 链接")
        }
        val path = call.path.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" } }
        return runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val request = Request.Builder().url(url).header("User-Agent", FETCH_UA).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val bytes = response.body?.bytes() ?: error("空响应")
                    val text = bytes.toString(Charsets.UTF_8)
                    val target = if (text.any { it == '\u0000' } || text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } > 8) {
                        // Binary payload: keep the bytes on disk, report the size only.
                        workspace.writeBytes(project, path, bytes).getOrThrow()
                        ToolOutcome("download", "$url → $path", true, "已下载 ${bytes.size} 字节")
                    } else {
                        workspace.write(project, path, text).getOrThrow()
                        ToolOutcome("download", "$url → $path", true, "已下载 ${bytes.size} 字节")
                    }
                    target
                }
            }
        }.getOrElse { ToolOutcome("download", url, false, it.message ?: "下载失败") }
    }

    /**
     * Fetches a page as plain text. Scripts, styles and markup are stripped before the model sees it:
     * what a coding agent wants from a doc page is the prose and the code samples, and sending raw
     * HTML would spend most of the budget on attributes.
     */
    private suspend fun fetch(url: String): ToolOutcome {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return ToolOutcome("web_fetch", clean, false, "只支持 http(s) 链接")
        }
        return runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val request = Request.Builder().url(clean).header("User-Agent", FETCH_UA).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val html = response.body?.string().orEmpty()
                    val text = html
                        .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
                        .replace(Regex("(?s)<[^>]+>"), " ")
                        .replace(Regex("&nbsp;?"), " ")
                        .replace(Regex("[ \\t]+"), " ")
                        .replace(Regex("\\n\\s*\\n+"), "\n\n")
                        .trim()
                    ToolOutcome(
                        tool = "web_fetch",
                        target = clean,
                        ok = true,
                        summary = "${text.length} 字",
                        detail = text.take(MAX_FETCH_CHARS),
                    )
                }
            }
        }.getOrElse { ToolOutcome("web_fetch", clean, false, it.message ?: "抓取失败") }
    }

    /** Device + workspace facts, so the model stops guessing what it is running on. */
    private fun systemInfo(project: CodeProject): ToolOutcome {
        val detail = buildString {
            appendLine("平台：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("项目：${project.name}")
            appendLine("项目根目录：${project.name}/（沙盒，路径相对它）")
            appendLine("文件数：${project.fileCount}")
            append("终端：不可用（手机端没有 shell、编译器与包管理器）")
        }
        return ToolOutcome("system_info", project.name, true, "设备信息", detail)
    }

    /* ---------------- prompts ---------------- */

    private fun planSection(planMode: Boolean): String {
        if (!planMode) return ""
        return "\n\n【计划模式（只读）】本轮只出计划，一个文件都不要改：create_file / edit_file / create_dir / delete / rename / copy / download 一律不可用，调了也会被拒绝。" +
            "可以先用只读工具把现状看清楚，再用 update_todos 列出步骤、用一两句说明关键取舍与假设，" +
            "最后用 {\"action\": \"plan\", \"plan\": \"计划全文\"} 交出完整计划等待用户批准。" +
            "用户点「开始执行」后，你会拿到同样的上下文重跑一遍，那时才动手。"
    }

    private fun instructionsSection(instructions: String): String {
        if (instructions.isBlank()) return ""
        return "\n\n【项目约定】以下是本项目 ${workspace.instructionsName()} 的内容，请在本项目内始终遵守：\n" +
            instructions.take(MAX_INSTRUCTIONS_CHARS)
    }

    private fun toolsSection(): String = buildString {
        append("【工具】用 ```json 围栏块调用工具，每块一个 JSON 对象，按出现顺序执行。需要并行时，在同一条回复里连续写多个块：\n")
        append("```json\n{\"action\": \"read_file\", \"path\": \"index.html\"}\n```\n\n")
        append("工具目录（action — 说明 / 示例）：\n")
        TOOL_SPECS.forEachIndexed { index, spec ->
            append("${index + 1}. ${spec.name} — ${spec.desc}\n   ${spec.example}\n")
        }
    }

    /**
     * The coding agent's standing instructions — ConeCode's core prompt, adapted to the phone.
     *
     * Sent on every round, so length is a running cost — but the expensive failure is not a long
     * prompt, it is a run that produces a blank page. The VERIFY step exists because this agent
     * cannot execute anything: nothing will tell it that the `<script src>` it wrote points at a
     * file it never created, so it has to check that itself before claiming to be done.
     */
    private fun systemPrompt(modelId: String, project: CodeProject) = buildString {
        append("你是 ConeCode 编程智能体，ConeAI 手机端的「写代码」工作台。你在一个沙盒项目目录里，直接读写文件完成编码任务。\n\n")
        append("【身份】被问「你是谁 / 什么模型」时如实回答：你是 ConeAI 的 ConeCode 编程智能体，当前模型为 $modelId。不要谎称自己是 Claude、ChatGPT 或 Gemini。\n\n")
        append("【工作流】按以下顺序工作；除紧急修复外不要跳步：\n")
        append("1. UNDERSTAND — 先读相关代码、约定、类型与库，确认文件、符号和 API 的存在与形状，形成有依据的判断再动手。\n")
        append("2. PLAN — 任务需要两步以上时，用 update_todos 建立简短有序的清单，并随进度保持更新（同时只有一项 in_progress）。\n")
        append("3. ACT & ORCHESTRATE — 做最小而完整的修改：匹配现有风格，保留无关改动，优先精确替换（edit_file）而不是整文件重写；注释说明「为什么」，不复述改动。\n")
        append("4. VERIFY — 改动后必须证明它能运行。本环境没有终端与编译器，无法执行代码，所以逐条静态核对：引用到的文件是否存在、id/class 是否对应、函数与变量是否都有定义、改动是否波及调用处。不确定就 read_file 回读。绝不要声称「已测试通过」。\n")
        append("5. REVIEW FROM THE TOP — 重新读原始需求，对照当前代码逐条检查，并把改过的文件从头读一遍；发现缺口就修好重来，直到没有问题才报告完成。\n\n")
        append(toolsSection())
        append("\n【规则】\n")
        append("- 路径一律相对项目根目录（如 src/app.js）；不写绝对路径，不用 .. 越出项目。\n")
        append("- 手机上没有终端、编译器、包管理器：不能运行、编译、安装依赖，也不要假装运行过或声称「已测试通过」。\n")
        append("- 网页是唯一能当场跑起来的类型：应用内用 file:// 直接打开 index.html。不要用需要构建的技术（JSX、TypeScript、Sass、打包器），不要引 CDN 外链——用户可能没网，一断链页面就废。CSS/JS 写成同目录的本地文件，或直接内联进 HTML。\n")
        append("- 网页按手机屏来做：带 viewport meta，可点区域不小于 44px，布局用 flex/grid 自适应，不要写死像素宽度。\n")
        append("- create_file / edit_file 的 content 必须是完整文件内容，禁止「…省略…」「其余保持不变」这类占位符。\n")
        append("- edit_file 的 old 必须与文件逐字一致（含缩进）且在文件中唯一；不唯一就多带几行上下文，或加 replaceAll=true。\n")
        append("- 代码要能直接用：该有的导入、错误处理、边界情况都写上，不留半成品、不留 TODO 注释。\n")
        append("- 并行规则：只读且相互独立的工具（read_file / list_dir / search / glob / memory_search / web_search / web_fetch / system_info）放在同一条回复的多个 json 块里一起执行；写入操作按依赖顺序逐个执行。\n")
        append("- 收尾规则：全部完成后停止发出动作，直接用一段纯文本 Markdown 总结（不要再输出 json 块）。没有动作就表示本轮结束。\n")
        append("- 一轮里最多用一个终结动作（ask_user / plan），用完不要再跟其它工具；最多 $MAX_ROUNDS 轮，高效推进，同一个文件不要反复读。\n\n")
        append("【提问】需求有歧义、或不同做法会明显改变结果时，先 ask_user 再动手，一次只问一个，把选项写清楚。\n")
        append("- 该问：「待办刷新后还留着（存 localStorage）还是刷新就清空？」\n")
        append("- 不该问：自己能合理决定的小事（变量名、配色、缩进）；以及问了只会得到「你看着办」的问题。\n")
        append("用户已经说清楚的不要反问；含糊但有明显合理默认值的，按默认做并在回答里说明你的假设。\n\n")
        append("【记忆】用户提出一条以后还要照办的要求时，只写一个地方：\n")
        append("- 换个新项目就不成立的 → 写进 ${workspace.instructionsName()}（先 read_file 再 create_file 回补充后的完整内容）。\n")
        append("  例：「本项目用 Vue 3」「接口放 api/ 目录」「这次先不做登录」\n")
        append("- 换个新项目照样成立的 → remember 记一条，它在用户以后每个项目里都生效。\n")
        append("  例：「偏好原生 JS，不用框架」「注释写中文」「缩进用两个空格」\n")
        append("判断只问一句：把这句话原样搬到用户下一个全新项目里，还成立吗？\n")
        append("注意：用户拒绝某个具体做法（「这里别用框架」）不等于他一贯讨厌它，除非他说了「以后都」「我一向」「所有项目」这类话，否则不要升级成长期习惯。\n")
        append("remember 每条 30 字以内、一次只记一条、【工作习惯】里已有的不重复记、绝不记密码密钥令牌。\n\n")
        append("【表达】围栏块之外的文字是给用户看的：说清你在做什么、为什么这么选，一两句就够。不要复述代码内容，不要写「好的，我将为您…」这类开场白。\n\n")
        append("【运行环境】Android 手机沙盒项目目录（本次项目「${project.name}」）；日期：$today；模型：$modelId。")
    }.trim() + "\n" + LocaleHelper.string(context, R.string.output_language_directive)

    private fun feedback(call: ToolCall, outcome: ToolOutcome): String {
        if (!outcome.ok) return "[${call.tool} ${outcome.target}] 失败：${outcome.summary}"
        return when (call.tool) {
            "read_file" -> buildString {
                append("[read_file ${call.path}] ${outcome.summary}\n")
                append(numbered(outcome.detail))
            }
            "list_dir" -> "[list_dir ${call.path}] 当前文件：\n${outcome.detail}"
            "search" -> "[search ${call.query}] ${outcome.summary}\n${outcome.detail.take(MAX_FEED_CHARS)}"
            "glob" -> "[glob ${call.query}] ${outcome.summary}\n${outcome.detail.take(MAX_FEED_CHARS)}"
            "remember" -> "[remember] ${outcome.summary}：${outcome.target}"
            "memory_search" -> "[memory_search ${call.query}] ${outcome.summary}\n${outcome.detail}"
            "ask_user" -> "[ask_user] 已向用户提问，等待回答"
            "plan" -> "[plan] 计划已交给用户确认"
            "update_todos" -> "[update_todos] 任务清单已更新，界面已展示给用户"
            "web_search" -> "[web_search ${call.query}] ${outcome.summary}\n${outcome.detail.take(MAX_FEED_CHARS)}"
            "web_fetch" -> "[web_fetch ${call.query}] ${outcome.summary}\n${outcome.detail.take(MAX_FEED_CHARS)}"
            "create_file" -> "[create_file ${call.path}] ${outcome.summary}（+${outcome.added} −${outcome.removed}）"
            "edit_file" -> "[edit_file ${call.path}] ${outcome.summary}（+${outcome.added} −${outcome.removed}）"
            "create_dir" -> "[create_dir ${call.path}] 已创建目录"
            "delete" -> "[delete ${call.path}] 已删除"
            "rename" -> "[rename ${outcome.target}] 已重命名"
            "copy" -> "[copy ${outcome.target}] 已复制"
            "download" -> "[download ${outcome.target}] ${outcome.summary}"
            "system_info" -> "[system_info]\n${outcome.detail}"
            else -> "[${call.tool}] ${outcome.summary}"
        }
    }

    private fun numbered(text: String): String =
        text.take(MAX_FEED_CHARS).lines().mapIndexed { i, line -> "${(i + 1).toString().padStart(4)}| $line" }
            .joinToString("\n")

    /**
     * Replays of earlier turns keep the model's reasoning but drop the file bodies it already wrote:
     * a couple of full-file writes would otherwise dominate the context window within three rounds,
     * and the current state of any file is one `read_file` away.
     */
    private fun compact(text: String): String =
        Regex("(```json\\s*\\{\\s*\"action\"\\s*:\\s*\"create_file\"[\\s\\S]*?)(```)").replace(text) { m ->
            val block = m.groupValues[1]
            if (block.length <= COMPACT_KEEP_CHARS) m.value
            else block.take(COMPACT_KEEP_CHARS) + "…（文件内容已写入，此处省略 ${block.length - COMPACT_KEEP_CHARS} 字符）" + m.groupValues[2]
        }

    private fun system(text: String) = ChatMessageDto("system", listOf(ContentPart.text(text)))
    private fun user(text: String) = ChatMessageDto("user", listOf(ContentPart.text(text)))
    private fun assistant(text: String) = ChatMessageDto("assistant", listOf(ContentPart.text(text)))

    private fun sum(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> a + b
    }

    private val today: String
        get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private data class ToolSpec(val name: String, val desc: String, val example: String)

    private companion object {
        /** Tools that change the project; blocked while a plan is still awaiting approval. */
        val MUTATING_TOOLS = setOf("create_file", "edit_file", "create_dir", "delete", "rename", "copy", "download")

        /** ConeCode's tool catalog, trimmed to what a phone sandbox can actually serve. */
        val TOOL_SPECS = listOf(
            ToolSpec("read_file", "读取项目文件全文（带行号）。", """{"action": "read_file", "path": "src/main.js"}"""),
            ToolSpec("list_dir", "列出项目目录树；path 为空时列出整个项目。", """{"action": "list_dir", "path": "src"}"""),
            ToolSpec("search", "全项目搜索文本；isRegex=true 时 query 按正则处理。", """{"action": "search", "query": "TODO"}"""),
            ToolSpec("glob", "按通配符列出文件（** 跨目录，* 不跨）。", """{"action": "glob", "pattern": "src/**/*.js"}"""),
            ToolSpec("create_file", "新建或整体覆盖文件，content 必须是完整内容。", """{"action": "create_file", "path": "app.js", "content": "完整文件内容"}"""),
            ToolSpec("edit_file", "精确替换：old 必须逐字出现且唯一；也可用 content 整体重写。", """{"action": "edit_file", "path": "app.js", "old": "旧片段", "new": "新片段"}"""),
            ToolSpec("create_dir", "新建目录（含父目录）。", """{"action": "create_dir", "path": "assets/img"}"""),
            ToolSpec("delete", "删除文件或目录。", """{"action": "delete", "path": "tmp.js"}"""),
            ToolSpec("rename", "重命名 / 移动文件或目录。", """{"action": "rename", "oldPath": "a.js", "newPath": "b.js"}"""),
            ToolSpec("copy", "复制文件或目录。", """{"action": "copy", "srcPath": "a.js", "destPath": "b.js"}"""),
            ToolSpec("download", "下载 URL 到项目文件。", """{"action": "download", "url": "https://example.com/x.js", "path": "vendor/x.js"}"""),
            ToolSpec("web_search", "联网搜索（拿不准的 API、库用法可以查）。", """{"action": "web_search", "query": "css grid 用法"}"""),
            ToolSpec("web_fetch", "抓取网页正文。", """{"action": "web_fetch", "url": "https://developer.mozilla.org/"}"""),
            ToolSpec("remember", "记住一条跨项目的长期工作习惯。", """{"action": "remember", "text": "注释写中文"}"""),
            ToolSpec("memory_search", "搜索长期记忆（全局事实与工作习惯）。", """{"action": "memory_search", "query": "注释"}"""),
            ToolSpec("update_todos", "更新可见任务清单；status 取 pending / in_progress / completed。", """{"action": "update_todos", "todos": [{"content": "读现有代码", "status": "completed"}, {"content": "写 index.html", "status": "in_progress"}]}"""),
            ToolSpec("ask_user", "需求有歧义或需要用户拍板时提问，一次只问一个。", """{"action": "ask_user", "question": "刷新后数据保留吗？", "options": ["保留（localStorage）", "清空"]}"""),
            ToolSpec("system_info", "查看设备与运行环境。", """{"action": "system_info"}"""),
            ToolSpec("plan", "计划模式下交出计划，等待用户批准。", """{"action": "plan", "plan": "计划全文"}"""),
        )

        const val MAX_ROUNDS = 24
        const val MAX_TOKENS = 8000
        const val TEMPERATURE = 0.2
        const val MAX_HISTORY_TURNS = 12
        const val MAX_DETAIL_CHARS = 24_000
        const val MAX_FEED_CHARS = 14_000
        const val COMPACT_KEEP_CHARS = 400
        const val MAX_FETCH_CHARS = 12_000
        const val MAX_INSTRUCTIONS_CHARS = 6_000
        const val MAX_SUMMARY_INPUT = 40_000
        const val FETCH_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122 Mobile Safari/537.36"
    }
}
