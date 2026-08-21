package com.cone.agent.code

import android.content.Context
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.remote.ChatChunk
import com.cone.agent.data.remote.LlmClient
import com.cone.agent.data.remote.WebSearchClient
import com.cone.agent.data.remote.dto.ChatMessageDto
import com.cone.agent.data.remote.dto.ContentPart
import com.cone.agent.data.repository.MemoryWrite
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.SettingsRepository
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
 * The coding brain behind 「写代码」: an observe → edit → verify loop over one sandboxed project.
 *
 * It reuses the app's own provider/model plumbing, so it works with whatever OpenAI- or
 * Anthropic-compatible endpoint the user configured, and — like the rest of ConeAI's tool use — it
 * needs no native function-calling support: the tools travel as tags in ordinary text.
 */
@Singleton
class CodeAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmClient: LlmClient,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val workspace: CodeWorkspace,
    private val codeMemory: CodeMemoryRepository,
    private val webSearchClient: WebSearchClient,
    private val httpClient: OkHttpClient,
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
            systemPrompt(selected.modelId) + instructionsSection(instructions) +
                codeMemory.promptSection() + planSection(planMode),
        )
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
                // Plain prose with no tool call is the model's final answer (a question, an
                // explanation, a refusal) — nothing left to run.
                emit(CodeEvent.Finished(tokens))
                return@flow
            }

            val results = StringBuilder()
            var stop: CodeEvent? = null
            for (call in calls) {
                when {
                    call.tool == "done" -> {
                        stop = CodeEvent.Finished(tokens)
                    }
                    // A question ends the round: the answer is the user's next message, and the
                    // history carries the question forward so the next run just continues.
                    call.tool == "ask" -> {
                        val question = call.content.ifBlank { call.query }.trim()
                        emit(CodeEvent.Tool(ToolOutcome("ask", question, true, "等待你的回答")))
                        stop = CodeEvent.Asked(question, tokens)
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
                            "计划模式下不能改文件，请先用 <plan/> 交出计划等用户确认",
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
                    append("\n\n请继续。全部完成后用 <done>一句话总结</done> 结束。")
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
        "read" -> read(project, call)
        "list" -> {
            val tree = workspace.treeText(project)
            ToolOutcome("list", project.name, true, "查看项目文件", tree)
        }
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
        "remember" -> remember(call.content.ifBlank { call.query })
        "todo" -> ToolOutcome(
            tool = "todo",
            target = "",
            ok = true,
            summary = "${call.content.lines().count { it.isNotBlank() }} 项",
            detail = call.content,
        )
        "web" -> webSearchClient.search(call.query, maxResults = 5).fold(
            onSuccess = { results ->
                ToolOutcome(
                    tool = "web",
                    target = call.query,
                    ok = true,
                    summary = "${results.size} 条结果",
                    detail = results.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" },
                )
            },
            onFailure = { ToolOutcome("web", call.query, false, it.message ?: "搜索失败") },
        )
        "fetch" -> fetch(call.query)
        "write" -> write(project, call)
        "edit" -> edit(project, call)
        "delete" -> workspace.delete(project, call.path).fold(
            onSuccess = { ToolOutcome("delete", call.path, true, "已删除") },
            onFailure = { ToolOutcome("delete", call.path, false, it.message.orEmpty()) },
        )
        "rename" -> workspace.rename(project, call.path, call.to).fold(
            onSuccess = { ToolOutcome("rename", "${call.path} → ${call.to}", true, "已重命名") },
            onFailure = { ToolOutcome("rename", call.path, false, it.message.orEmpty()) },
        )
        else -> ToolOutcome(call.tool, call.path, false, "未知工具：${call.tool}")
    }

    /**
     * Stores one standing preference of the user's. Rejected content is reported honestly rather
     * than silently swallowed — the model is told what happened so it does not try again next turn.
     */
    private suspend fun remember(text: String): ToolOutcome {
        val fact = text.trim()
        if (fact.isBlank()) return ToolOutcome("remember", "", false, "remember 需要给出要记住的一句话")
        return when (codeMemory.add(fact)) {
            MemoryWrite.ADDED -> ToolOutcome("remember", fact, true, "已记住")
            MemoryWrite.REFINED -> ToolOutcome("remember", fact, true, "已更新")
            MemoryWrite.DUPLICATE -> ToolOutcome("remember", fact, true, "已经记住过了")
            MemoryWrite.REJECTED ->
                ToolOutcome("remember", fact, false, "这条不能存入工作习惯（过长、为空，或含敏感信息）")
        }
    }

    private suspend fun read(project: CodeProject, call: ToolCall): ToolOutcome =
        workspace.read(project, call.path).fold(
            onSuccess = { text ->
                ToolOutcome(
                    tool = "read",
                    target = call.path,
                    ok = true,
                    summary = "${text.lines().size} 行",
                    detail = text.take(MAX_DETAIL_CHARS),
                )
            },
            onFailure = { ToolOutcome("read", call.path, false, it.message.orEmpty()) },
        )

    private suspend fun write(project: CodeProject, call: ToolCall): ToolOutcome {
        if (call.path.isBlank()) return ToolOutcome("write", "", false, "缺少 path 属性")
        val existed = workspace.exists(project, call.path)
        val before = if (existed) workspace.read(project, call.path).getOrDefault("") else ""
        return workspace.write(project, call.path, call.content).fold(
            onSuccess = {
                val (added, removed) = CodeDiff.stats(before, call.content)
                ToolOutcome(
                    tool = "write",
                    target = call.path,
                    ok = true,
                    summary = if (existed) "已覆盖" else "已新建",
                    added = added,
                    removed = removed,
                    diff = CodeDiff.compute(before, call.content),
                )
            },
            onFailure = { ToolOutcome("write", call.path, false, it.message.orEmpty()) },
        )
    }

    /**
     * Literal, unique string replacements applied in order.
     *
     * Both guards matter: a match that appears twice would let the model silently patch the wrong
     * occurrence, and a match that appears zero times usually means the model is editing against a
     * file it only imagined — better to say so and let it read. The whole batch is checked and
     * applied in memory first, so a bad pair later in the list cannot leave the file half-edited.
     */
    private suspend fun edit(project: CodeProject, call: ToolCall): ToolOutcome {
        val before = workspace.read(project, call.path).getOrElse {
            return ToolOutcome("edit", call.path, false, it.message.orEmpty())
        }
        if (call.edits.isEmpty() || call.edits.all { it.first.isEmpty() }) {
            return ToolOutcome("edit", call.path, false, "<old> 为空；整文件替换请改用 write")
        }
        var after = before
        call.edits.forEachIndexed { index, (old, new) ->
            val label = if (call.edits.size > 1) "第 ${index + 1} 处：" else ""
            if (old.isEmpty()) {
                return ToolOutcome("edit", call.path, false, "$label<old> 为空")
            }
            val occurrences = after.split(old).size - 1
            if (occurrences == 0) {
                return ToolOutcome("edit", call.path, false, "${label}文件中找不到 <old> 的内容，请先 read 再改")
            }
            if (occurrences > 1) {
                return ToolOutcome("edit", call.path, false, "$label<old> 出现了 $occurrences 次，请提供更长、唯一的片段")
            }
            after = after.replace(old, new)
        }
        return workspace.write(project, call.path, after).fold(
            onSuccess = {
                val (added, removed) = CodeDiff.stats(before, after)
                ToolOutcome(
                    tool = "edit",
                    target = call.path,
                    ok = true,
                    summary = if (call.edits.size > 1) "已修改 ${call.edits.size} 处" else "已修改",
                    added = added,
                    removed = removed,
                    diff = CodeDiff.compute(before, after),
                )
            },
            onFailure = { ToolOutcome("edit", call.path, false, it.message.orEmpty()) },
        )
    }

    /**
     * Fetches a page as plain text. Scripts, styles and markup are stripped before the model sees it:
     * what a coding agent wants from a doc page is the prose and the code samples, and sending raw
     * HTML would spend most of the budget on attributes.
     */
    private suspend fun fetch(url: String): ToolOutcome {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return ToolOutcome("fetch", clean, false, "只支持 http(s) 链接")
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
                        tool = "fetch",
                        target = clean,
                        ok = true,
                        summary = "${text.length} 字",
                        detail = text.take(MAX_FETCH_CHARS),
                    )
                }
            }
        }.getOrElse { ToolOutcome("fetch", clean, false, it.message ?: "抓取失败") }
    }

    /** The extra rules that turn a run into a planning pass. Empty when plan mode is off. */
    /** The extra rules that turn a run into a planning pass. Empty when plan mode is off. */
    private fun planSection(planMode: Boolean): String {
        if (!planMode) return ""
        return "\n\n【计划模式】本轮只出计划，一个文件都不要改。" +
            "可以用 <list/>、<read/>、<search/>、<glob/> 把现状看清楚；" +
            "write / edit / delete / rename 本轮一律不可用，调了也会被拒绝。" +
            "用 <todo> 写出你打算怎么做（每行一步），再用一两句说明关键取舍和你做的假设，" +
            "最后用 <plan/> 结束等用户确认。用户点「开始执行」后，你会拿到同样的上下文重跑一遍，那时才动手。"
    }

    private fun instructionsSection(instructions: String): String {
        if (instructions.isBlank()) return ""
        return "\n\n【项目约定】以下是本项目 ${workspace.instructionsName()} 的内容，请在本项目内始终遵守：\n" +
            instructions.take(MAX_INSTRUCTIONS_CHARS)
    }

    /* ---------------- prompts ---------------- */

    private fun feedback(call: ToolCall, outcome: ToolOutcome): String {
        if (!outcome.ok) return "[${call.tool} ${outcome.target}] 失败：${outcome.summary}"
        return when (call.tool) {
            "read" -> buildString {
                append("[read ${call.path}] ${outcome.summary}\n")
                append(numbered(outcome.detail))
            }
            "list" -> "[list] 当前文件：\n${outcome.detail}"
            "search" -> "[search ${call.query}] ${outcome.summary}\n${outcome.detail.take(MAX_FEED_CHARS)}"
            "glob" -> "[glob ${call.query}] ${outcome.summary}\n${outcome.detail.take(MAX_FEED_CHARS)}"
            "remember" -> "[remember] ${outcome.summary}：${outcome.target}"
            "ask" -> "[ask] 已向用户提问，等待回答"
            "plan" -> "[plan] 计划已交给用户确认"
            "todo" -> "[todo] 任务清单已更新，界面已展示给用户"
            "web" -> "[web ${call.query}] ${outcome.summary}\n${outcome.detail.take(MAX_FEED_CHARS)}"
            "fetch" -> "[fetch ${call.query}] ${outcome.summary}\n${outcome.detail.take(MAX_FEED_CHARS)}"
            "write" -> "[write ${call.path}] ${outcome.summary}（+${outcome.added} −${outcome.removed}）"
            "edit" -> "[edit ${call.path}] 已修改（+${outcome.added} −${outcome.removed}）"
            "delete" -> "[delete ${call.path}] 已删除"
            "rename" -> "[rename ${outcome.target}] 已重命名"
            else -> "[${call.tool}] ${outcome.summary}"
        }
    }

    private fun numbered(text: String): String =
        text.take(MAX_FEED_CHARS).lines().mapIndexed { i, line -> "${(i + 1).toString().padStart(4)}| $line" }
            .joinToString("\n")

    /**
     * Replays of earlier turns keep the model's reasoning but drop the file bodies it already wrote:
     * a couple of full-file writes would otherwise dominate the context window within three rounds,
     * and the current state of any file is one `read` away.
     */
    private fun compact(text: String): String =
        Regex("(<write\\b[^>]*>)([\\s\\S]*?)(</write>)").replace(text) { m ->
            if (m.groupValues[2].length <= COMPACT_KEEP_CHARS) {
                m.value
            } else {
                "${m.groupValues[1]}（内容已写入文件，此处省略 ${m.groupValues[2].length} 字符）${m.groupValues[3]}"
            }
        }

    /**
     * The coding agent's standing instructions.
     *
     * Sent on every round, so length is a running cost — but the expensive failure is not a long
     * prompt, it is a run that produces a blank page. The 自查 step exists because this agent cannot
     * execute anything: nothing will tell it that the `<script src>` it wrote points at a file it
     * never created, so it has to check that itself before claiming to be done.
     */
    private fun systemPrompt(modelId: String) =
        """
你是 ConeAI 代码助手，运行在用户的 Android 手机上，底层模型是用户自行接入的 "$modelId"。你在一个沙盒项目目录里直接读写文件完成编码任务。

【身份】被问「你是谁 / 什么模型」时如实回答：你是 ConeAI，当前模型为 $modelId。不要谎称自己是 Claude、ChatGPT 或 Gemini。

【环境】
- 路径一律相对项目根目录（如 src/app.js）；不写绝对路径，不用 .. 越出项目。
- 手机上没有终端、编译器、包管理器：你不能运行、编译、安装依赖，也绝不要假装运行过或声称「已测试通过」。
- 网页是唯一能当场跑起来的类型：应用内用 file:// 直接打开 index.html。所以不要用需要构建的技术（JSX、TypeScript、Sass、打包器），也不要引 CDN 外链——用户可能没网，一断链页面就废。CSS/JS 写成同目录的本地文件，或直接内联进 HTML。

【工具】用下面的标签调用工具。标签必须顶格独立出现，前后不要包裹 ``` 代码块：
<list/>                                查看项目全部文件
<read path="app.js"/>                  读取文件（会带行号返回）
<search q="TODO"/>                     全项目搜索文本；加 re="1" 表示 q 是正则
<glob pattern="src/**/*.js"/>          按通配符列出文件（** 跨目录，* 不跨）
<write path="app.js">完整文件内容</write>   新建或整体覆盖一个文件
<edit path="app.js"><old>原文片段</old><new>新片段</new></edit>   精确替换文件里的一段文本
<delete path="tmp.js"/>                删除文件或目录
<rename path="a.js" to="b.js"/>        重命名 / 移动
<todo>任务清单</todo>                    更新任务清单，直接展示给用户看
<web q="css grid 用法"/>                联网搜索（拿不准的 API、库用法可以查）
<fetch url="https://..."/>             抓取网页正文
<remember>用户的长期工作习惯</remember>     记住用户跨项目一贯的偏好
<ask>要问用户的问题</ask>                 需求不明确时提问，会停下等回答
<plan/>                                计划模式下交出计划，等用户确认
<done>一句话总结你做了什么</done>          任务完成，结束本轮

【一个任务怎么走】
1. 先看再动：不清楚项目里有什么就 <list/>，要改的文件先 <read/>。绝不修改没读过的文件。
2. 三步以上的任务先用 <todo> 列清单，之后每完成一步重新输出整份清单并更新勾选。一两步的小事不用列：
<todo>
- [x] 读现有代码
- [>] 写 index.html
- [ ] 加交互逻辑
</todo>
   [ ] 待办、[>] 正在做（同时最多一项）、[x] 已完成。
3. 动手：新建或整体重写用 <write>，局部修改用 <edit>。
4. **收尾自查**——你跑不了代码，这一步就是你唯一的测试。宣布完成前逐条核对刚写的内容：
   - HTML 里每个 src / href 指向的文件，是否都真的创建了？
   - JS 里 getElementById / querySelector 用到的 id、class，是否在 HTML 里真的存在？拼写一致吗？
   - 调用到的函数和变量是否都有定义？事件监听是否真的绑上了？
   - 改动是否波及别的文件（改了函数名，调用处跟着改了吗）？
   记不清就 <read/> 回读确认，不要凭印象。用户点开预览是白屏，绝大多数就是漏了这一步。
5. 用 <done> 收尾，一句话说明改了什么。

【写代码的硬要求】
- <write> 必须是**完整**的文件内容，禁止「…省略…」「其余保持不变」这类占位符——你写什么，文件里就只剩什么。
- <edit> 的 <old> 必须与文件逐字一致（含缩进）且在文件中唯一；不唯一就多带几行上下文。一次 <edit> 可以放多组 <old>/<new>，按顺序执行，任何一组匹配失败则整次不生效。
- 代码要能直接用：该有的导入、错误处理、边界情况都写上，不留半成品、不留 TODO 注释。
- 网页按手机屏来做：带 viewport meta，可点区域不小于 44px，布局用 flex/grid 自适应，不要写死像素宽度。

【本轮怎么结束】三选一，一轮里只用一个，用完不要再跟其它工具：
- <done>  —— 做完了
- <ask>   —— 需要用户拿主意
- <plan/> —— 计划模式下交出计划
最多 $MAX_ROUNDS 轮，高效推进：同一个文件不要反复读，一次能连着调用多个工具就不要拆成多轮。

【提问】需求有歧义、或不同做法会明显改变结果时，先 <ask> 再动手，一次只问一个，把选项写清楚。
- 该问：「待办刷新后还留着（存 localStorage）还是刷新就清空？」
- 不该问：自己能合理决定的小事（变量名、配色、缩进）；以及问了只会得到「你看着办」的问题。
用户已经说清楚的不要反问；含糊但有明显合理默认值的，按默认做并在回答里说明你的假设。

【记忆】用户提出一条以后还要照办的要求时，**只写一个地方**：
- 换个新项目就不成立的 → 写进 ${workspace.instructionsName()}（先 <read/> 再 <write> 回补充后的完整内容）。
  例：「本项目用 Vue 3」「接口放 api/ 目录」「这次先不做登录」
- 换个新项目照样成立的 → <remember> 记一条，它在用户以后每个项目里都生效。
  例：「偏好原生 JS，不用框架」「注释写中文」「缩进用两个空格」
判断只问一句：把这句话原样搬到用户下一个全新项目里，还成立吗？
注意：用户拒绝某个具体做法（「这里别用框架」）不等于他一贯讨厌它，除非他说了「以后都」「我一向」「所有项目」这类话，否则不要升级成长期习惯。
<remember> 每条 30 字以内、一次只记一条、【工作习惯】里已有的不重复记、绝不记密码密钥令牌。

【表达】标签之外的文字是给用户看的：说清你在做什么、为什么这么选，一两句就够。不要复述代码内容，不要写「好的，我将为您…」这类开场白。
""".trim() + "\n" + LocaleHelper.string(context, R.string.output_language_directive)

    private fun system(text: String) = ChatMessageDto("system", listOf(ContentPart.text(text)))
    private fun user(text: String) = ChatMessageDto("user", listOf(ContentPart.text(text)))
    private fun assistant(text: String) = ChatMessageDto("assistant", listOf(ContentPart.text(text)))

    private fun sum(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> a + b
    }

    private companion object {
        /** Tools that change the project; blocked while a plan is still awaiting approval. */
        val MUTATING_TOOLS = setOf("write", "edit", "delete", "rename")

        const val MAX_ROUNDS = 12
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
