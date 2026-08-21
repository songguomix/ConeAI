package com.cone.agent.agent

import android.content.Context
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.remote.ChatChunk
import com.cone.agent.data.remote.LlmClient
import com.cone.agent.data.remote.dto.ChatMessageDto
import com.cone.agent.data.remote.dto.ContentPart
import com.cone.agent.agent.action.ActionType
import com.cone.agent.agent.action.PlannedAction
import com.cone.agent.data.repository.ChatRepository
import com.cone.agent.data.repository.MemoryRepository
import com.cone.agent.data.repository.MemoryWrite
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.domain.model.ConversationMode
import com.cone.agent.domain.model.Sender
import com.cone.agent.domain.model.UiMessage
import com.cone.agent.mcp.McpManager
import com.cone.agent.data.remote.WebSearchClient
import com.cone.agent.web.DailyInfoService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "just answer me" brain: a plain text Q&A turn against the selected model, with no screen
 * capture or device automation. Shared by the main chat screen's 问答 mode and the voice assistant's
 * 思考 mode.
 */
@Singleton
class ChatResponder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmClient: LlmClient,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val dailyInfo: DailyInfoService,
    private val webSearchClient: WebSearchClient,
    private val memoryRepository: MemoryRepository,
    private val memoryExtractor: MemoryExtractor,
    private val memoryContext: MemoryContext,
    private val chatRepository: ChatRepository,
    private val executor: ActionExecutor,
    private val mcpManager: McpManager,
    private val json: Json,
) {

    /**
     * The identity has to be pinned to the *actual* configured model: otherwise the underlying model
     * answers "who are you" with its trained identity (e.g. "I'm Claude, made by Anthropic"), which is
     * wrong here — the user brings their own model via Provider 管理.
     */
    private fun systemPrompt(modelId: String, dynamicSections: String) =
        """
你是 ConeAI，运行在用户手机上的 AI 助手，底层模型是用户自行接入的 "$modelId"。请发挥该模型的全部能力，给出专业、深入、准确、实用的回答。

【身份】被问「你是谁 / 什么模型 / 谁开发的」时如实回答：你是 ConeAI，当前模型为 $modelId。不得谎称自己是 Claude、ChatGPT、Gemini、文心一言等，也不得编造与 $modelId 不符的开发方（如 Anthropic、OpenAI、Google）。

【质量】先给结论或可直接照做的答案，再按需展开；复杂问题分点、分步，必要的推导、示例、边界情况不要省。准确第一：不确定就明说，宁可承认局限也绝不编造事实、数据或引用；若上文给了联网搜索结果，请结合作答并按 [n] 标注来源。

【富媒体】用 Markdown 组织回答：标题、列表、表格、引用、分隔线、带语言标注的代码块。一图胜千言时直接用 ![描述](URL) 输出，应用会渲染——图表用 QuickChart（参数须 URL 编码），流程/架构/时序图把 Mermaid 代码 base64 后拼 https://mermaid.ink/img/<base64>，其余用已知真实公开图片；下载/跳转用 [名称](https链接)。所有链接与图片地址必须真实可访问，绝不编造，帮助不大就不放。

【数学排版】本应用不渲染 LaTeX，绝不要输出 $…$、\frac、\times 之类的 LaTeX 语法。数学内容直接用 Unicode 符号书写：上下标写 x²、x₁、aⁿ，根号写 √2、√(x+1)，分数写 1/2 或 (a+b)/(c+d)，运算与关系符号用 ×、÷、·、±、≤、≥、≠、≈、π、∑、∫、∞、°。多步推导垂直分行、每行一个等式，对齐排版可用代码块。

【工具调用】需要实时信息时，你可以调取这些免费信息接口：weather(城市天气)、news(新闻头条)、exchange_rate(汇率，query 如 "USD CNY 100")、crypto(加密货币价格)、stock(股票行情，query 为带市场前缀的代码：sh600519/sz000001/hk00700/usAAPL)、wiki(维基百科词条摘要)、holiday(法定节假日)、route(路线距离与驾车耗时，query 为 "起点 到 终点")、music(歌曲信息：歌手/专辑/试听链接)、lyrics(歌词)、ip(本机公网 IP 与归属地)、time(世界时间，query 为 IANA 时区名如 Asia/Tokyo)、air_quality(空气质量 AQI/PM2.5，query 为城市，可空)、book(书籍搜索)、dict(英语词典：音标+释义)、gold(金银现货价，query 为 黄金/白银)、web_search(ConeSearch 本地+联网混合搜索，query 为关键词，返回本地索引段落+联网结果，已做8信号排序/MMR去重/token预算打包)、fetch(抓取清洗单页为Markdown，query 为URL)、index_url(将URL内容抓取并入库本地索引，query 为URL)。调取方式：单独输出一行 JSON、不附带任何其它文字或代码块，如 {"tool":"weather","query":"北京"}。应用会执行并把结果回传给你，之后你再给出正式回答（最多连续调取 3 次）。不需要实时信息就直接回答，不要为常识问题调取工具。所有联网搜索已统一走 ConeSearch（本地FTS5+远端Bing抓取+排名/去重/预算打包），无需区分本地/联网。

【系统接口】用户请求对应操作时，你可以直接调用这些一次性系统接口（同上格式，多一个可选 "app" 字段）：alarm(设闹钟/计时器，query 为 "HH:MM 标签" 或 "10分钟 标签")、flashlight(手电筒，query 为 on/off)、call(打开拨号盘，query 为电话号码)、sms(短信草稿，query 为内容，app 为收件号码可省)、email(邮件草稿，query 为内容，app 为收件地址可省)、navigate(发起导航，query 为目的地，app 可指定地图)、play_music(搜索并播放音乐，query 为歌曲/歌手，app 可指定音乐应用)、shopping(商品搜索，query 为商品，app 可指定 淘宝/京东/拼多多)、share(系统分享文字，query 为要分享的完整内容，app 为目标应用如 微信/QQ)、open_app(打开应用，query 为应用名)、open_settings(打开系统设置页，query 如 wifi/蓝牙/电池)。例：{"tool":"alarm","query":"07:30 起床"}、{"tool":"share","query":"整理好的文字","app":"微信"}。这些接口一步直达且可逆（拨号盘不会自动拨出，短信/邮件只是草稿，分享由用户在对方界面确认），执行结果会回传给你，之后你向用户简短确认结果。
$dynamicSections
【边界】除上述接口外，你不能进行屏幕自动化（看屏幕、点击、逐字输入——那是「智能体」模式的能力）。接口能一步完成的直接调接口；都做不到时如实说明，不要假装已完成操作。

【容错理解】用户的问题可能来自语音转写或含笔误（同音字、错别字、漏字）：请先按最合理的真实意图理解再作答，不要拘泥字面，也不要因个别错字反问。
""".trim() + "\n" +
            // Respond in the app's selected UI language (translated per-locale).
            LocaleHelper.string(context, R.string.output_language_directive)

    /**
     * Answers [question], optionally using [history] (older chat turns) for context. Returns the
     * assistant's reply text, or a failure with a user-readable message.
     */
    /**
     * Answers [question], optionally using [history] for context and an attached [imageDataUrl]
     * (`data:image/...;base64,...`) for a vision question. Uses the dedicated 问答 model when set,
     * otherwise falls back to the agent's model.
     */
    /** A model answer plus the total tokens the provider reported (null if not provided). */
    data class Answer(val text: String, val totalTokens: Int?)

    suspend fun answer(
        question: String,
        history: List<UiMessage> = emptyList(),
        imageDataUrls: List<String> = emptyList(),
        incognito: Boolean = false,
        onAgentTask: (suspend (String) -> String)? = null,
    ): Result<Answer> {
        val selected = settingsRepository.chatModel.first()
            ?: settingsRepository.selectedModel.first()
            ?: return Result.failure(IllegalStateException("尚未选择模型，请先在设置中选择一个模型。"))
        val provider = providerRepository.resolve(selected.providerId)
            ?: return Result.failure(IllegalStateException("所选模型对应的 Provider 不存在。"))

        val (effQuestion, effImages) = resolveImages(question, imageDataUrls)
        val memoryActive = memoryActive(incognito)
        val sections = memorySection(memoryActive, question) + agentTaskSection(onAgentTask != null) + mcpSection()
        // Tool loop: a reply that is exactly one {"tool":…} line runs the free info API and asks
        // again with the result — works with any model, no function-calling support required.
        val toolTurns = mutableListOf<ChatMessageDto>()
        var tokens: Int? = null
        var rounds = 0
        while (true) {
            val reply = llmClient.chat(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                model = selected.modelId,
                messages = buildMessages(selected.modelId, sections, effQuestion, history, effImages) + toolTurns,
                protocol = provider.protocol,
                temperature = 0.7,
            ).getOrElse { return Result.failure(it) }
            tokens = sumTokens(tokens, reply.totalTokens)
            val tool = (if (rounds < MAX_TOOL_ROUNDS) parseToolCall(reply.content, memoryActive, onAgentTask != null) else null)
                ?: run {
                    memoryExtractor.observe(question, reply.content, incognito = !memoryActive, onSaved = ::noteMemoryChanges)
                    return Result.success(Answer(reply.content, tokens))
                }
            // Handoff to the agent ends the Q&A turn: the confirmation line IS the answer.
            if (tool.first == AGENT_TOOL && onAgentTask != null) {
                return Result.success(Answer(onAgentTask(tool.second?.trim().orEmpty().ifBlank { question }), tokens))
            }
            rounds++
            toolTurns += ChatMessageDto(role = "assistant", content = listOf(ContentPart.text(reply.content)))
            toolTurns += ChatMessageDto(
                role = "user",
                content = listOf(ContentPart.text(toolResultPrompt(runTool(tool.first, tool.second, tool.third)))),
            )
        }
    }

    /**
     * Streaming variant of [answer]: emits [ChatChunk]s (text deltas, then a final one with tokens)
     * so the UI can show the reply appearing token-by-token, ChatGPT-style. Throws (into the Flow) if
     * no model/provider is configured or the request fails, so the caller can fall back to [answer].
     */
    fun answerStream(
        question: String,
        history: List<UiMessage> = emptyList(),
        imageDataUrls: List<String> = emptyList(),
        incognito: Boolean = false,
        onAgentTask: (suspend (String) -> String)? = null,
    ): Flow<ChatChunk> = flow {
        val selected = settingsRepository.chatModel.first()
            ?: settingsRepository.selectedModel.first()
            ?: throw IllegalStateException("尚未选择模型，请先在设置中选择一个模型。")
        val provider = providerRepository.resolve(selected.providerId)
            ?: throw IllegalStateException("所选模型对应的 Provider 不存在。")
        val (effQuestion, effImages) = resolveImages(question, imageDataUrls)
        val memoryActive = memoryActive(incognito)
        val sections = memorySection(memoryActive, question) + agentTaskSection(onAgentTask != null) + mcpSection()

        // Same tool loop as [answer], adapted to streaming: a reply that opens with '{' is held back
        // (a tool call is one short JSON line — it must not render as the answer); everything else
        // streams through live. Tool rounds stay invisible; only the final reply reaches the UI.
        val toolTurns = mutableListOf<ChatMessageDto>()
        var tokens: Int? = null
        var rounds = 0
        while (true) {
            val buffer = StringBuilder()
            var holding = true
            var roundTokens: Int? = null
            llmClient.chatStream(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                model = selected.modelId,
                messages = buildMessages(selected.modelId, sections, effQuestion, history, effImages) + toolTurns,
                protocol = provider.protocol,
                temperature = 0.7,
            ).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Delta -> {
                        if (!holding) {
                            emit(chunk)
                        } else {
                            buffer.append(chunk.text)
                            val seen = buffer.trimStart()
                            // Flush once it clearly isn't a tool call: doesn't start with '{', or is
                            // already far longer than any tool JSON line could be.
                            if ((seen.isNotEmpty() && seen[0] != '{') || buffer.length > MAX_TOOL_JSON_CHARS) {
                                holding = false
                                emit(ChatChunk.Delta(buffer.toString()))
                            }
                        }
                    }
                    is ChatChunk.Done -> roundTokens = chunk.totalTokens
                }
            }
            tokens = sumTokens(tokens, roundTokens)
            val tool = if (holding && rounds < MAX_TOOL_ROUNDS) {
                parseToolCall(buffer.toString(), memoryActive, onAgentTask != null)
            } else {
                null
            }
            if (tool == null) {
                if (holding && buffer.isNotEmpty()) emit(ChatChunk.Delta(buffer.toString()))
                emit(ChatChunk.Done(tokens))
                memoryExtractor.observe(question, buffer.toString(), incognito = !memoryActive, onSaved = ::noteMemoryChanges)
                return@flow
            }
            // Handoff to the agent ends the Q&A turn: the confirmation line IS the answer.
            if (tool.first == AGENT_TOOL && onAgentTask != null) {
                emit(ChatChunk.Delta(onAgentTask(tool.second?.trim().orEmpty().ifBlank { question })))
                emit(ChatChunk.Done(tokens))
                return@flow
            }
            rounds++
            toolTurns += ChatMessageDto(role = "assistant", content = listOf(ContentPart.text(buffer.toString())))
            toolTurns += ChatMessageDto(
                role = "user",
                content = listOf(ContentPart.text(toolResultPrompt(runTool(tool.first, tool.second, tool.third)))),
            )
        }
    }

    /* ---------------- 免费信息工具（问答只读，不执行任何手机操作） ---------------- */

    /**
     * Accepts a reply that is exactly one tool-call JSON object (whitespace / a stray code fence
     * allowed) and returns (tool, query, app). Anything with prose around it is a normal answer.
     * Memory tools are only recognised while memory is active (enabled and not incognito).
     */
    private fun parseToolCall(reply: String, allowMemory: Boolean, allowAgent: Boolean): Triple<String, String?, String?>? {
        val trimmed = reply.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val j = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val tool = j.optString("tool").trim().lowercase()
            .takeIf {
                it in TOOLS || it in SYSTEM_TOOLS || it == MCP_TOOL ||
                    (allowMemory && it in MEMORY_TOOLS) || (allowAgent && it == AGENT_TOOL)
            }
            ?: return null
        val query = j.optString("query").takeIf { it.isNotBlank() }
            ?: j.optString("text").takeIf { it.isNotBlank() }
            ?: j.optString("arguments").takeIf { it.isNotBlank() }
        val app = j.optString("app").takeIf { it.isNotBlank() }
            ?: j.optString("server").takeIf { it.isNotBlank() }
        return Triple(tool, query, app)
    }

    private suspend fun runTool(tool: String, query: String?, app: String? = null): String = when (tool) {
        "weather" -> dailyInfo.weather(query)
        "news" -> dailyInfo.news(query)
        "exchange_rate" -> dailyInfo.exchangeRate(query)
        "crypto" -> dailyInfo.crypto(query)
        "stock" -> dailyInfo.stock(query)
        "wiki" -> dailyInfo.wiki(query)
        "holiday" -> dailyInfo.holiday(query)
        "route" -> dailyInfo.route(query)
        "music" -> dailyInfo.music(query)
        "lyrics" -> dailyInfo.lyrics(query)
        "ip" -> dailyInfo.ipInfo()
        "time" -> dailyInfo.worldTime(query)
        "air_quality" -> dailyInfo.airQuality(query)
        "book" -> dailyInfo.book(query)
        "dict" -> dailyInfo.dict(query)
        "gold" -> dailyInfo.gold(query)
        "web_search", "cone_search", "search" -> coneSearch(query)
        "fetch", "fetch_url" -> coneFetch(query)
        "index_url", "index" -> coneIndex(query)
        "remember" -> remember(query)
        "forget" -> forget(query)
        "recall_history" -> recallHistory(query)
        MCP_TOOL -> runMcpTool(query, app)
        in SYSTEM_TOOLS -> runSystemTool(SYSTEM_TOOLS.getValue(tool), query, app)
        else -> ""
    }

    /**
     * 问答的系统接口：与智能体共用 [ActionExecutor] 的一次性直达调用（拨号盘/短信邮件草稿/闹钟/
     * 手电筒/导航/播放/分享…），可逆或需用户在对方界面再确认，因此无需高风险弹窗。屏幕自动化
     * （点击/输入）不在此列——那要走 agent_task 转智能体。
     */
    private suspend fun coneSearch(query: String?): String {
        val q = query?.trim().orEmpty().ifBlank { return "web_search 需要 query 关键词" }
        return try {
            val r = webSearchClient.searchHybrid(q, maxResults = 8, maxTokens = 3500)
            if (r.results.isEmpty()) "ConeSearch 未找到与「$q」相关的内容，可换更短关键词或尝试 fetch 具体URL。"
            else r.context.take(6000)
        } catch (e: Exception) { "ConeSearch 搜索失败: ${e.message}，可改用 web_search 换关键词重试。" }
    }

    private suspend fun coneFetch(query: String?): String {
        val url = query?.trim().orEmpty().ifBlank { return "fetch 需要 query 为 URL" }
        return try {
            val r = webSearchClient.fetch(url, maxTokens = 4000)
            if (r.content.isBlank()) "抓取「$url」为空或失败" else "标题：${r.title}\n来源：${r.url}\n\n${r.content.take(6000)}"
        } catch (e: Exception) { "fetch 失败: ${e.message}" }
    }

    private suspend fun coneIndex(query: String?): String {
        val url = query?.trim().orEmpty().ifBlank { return "index_url 需要 query 为 URL" }
        return try {
            val id = webSearchClient.index(url)
            "已索引「$url」(docId=$id)，后续 web_search 可命中本地段落。"
        } catch (e: Exception) { "index 失败: ${e.message}" }
    }

    private suspend fun runSystemTool(type: ActionType, query: String?, app: String?): String {
        val result = executor.execute(PlannedAction(thought = "", type = type, text = query, app = app))
        val head = if (result.success) result.message else "执行失败：${result.message}"
        return listOfNotNull(head, result.observation).joinToString("\n")
    }

    private suspend fun runMcpTool(query: String?, app: String?): String {
        val text = query ?: return "mcp_call 需要 query"
        val raw = if (app != null) "$text {\"_server\":\"$app\"}" else text
        val result = mcpManager.callToolRaw(app, text)
        return result.getOrElse { "MCP 调用失败: ${it.message}" }
    }

    private fun mcpSection(): String {
        val s = mcpManager.promptSection()
        return if (s.isBlank()) "" else "\n$s\n"
    }

    /**
     * The 【执行任务】 prompt block — present only when the caller can actually hand a task to the
     * agent (main Q&A / voice assistant; incognito and headless callers pass no handler).
     */
    private fun agentTaskSection(available: Boolean): String {
        if (!available) return ""
        return "\n【执行任务】当用户的请求需要**多步屏幕操作**才能完成（在应用里找到某人发消息并发送、下单购买、填表报名、" +
            "批量整理等——上面的系统接口一步做不到的）：单独输出一行 " +
            """{"tool":"agent_task","query":"交给智能体执行的完整任务指令，一句话说清目标与关键信息"}""" +
            "，应用会自动切换到智能体模式替用户执行。判断优先级：能用文字回答的直接回答 → 一步接口能完成的用【系统接口】 → " +
            "确需多步屏幕操作才转 agent_task。\n"
    }

    /* ---------------- 问答记忆（Gemini 式，仅存本机） ---------------- */

    private suspend fun memoryActive(incognito: Boolean): Boolean =
        !incognito && memoryRepository.enabled.first()

    /** The 【记忆】 prompt block: saved facts + when/how to call remember / forget. Empty when off. */
    private suspend fun memorySection(active: Boolean, question: String = ""): String {
        if (!active) return ""
        val memories = memoryContext.relevant(question, MemoryContext.CHAT_LIMIT)
        return buildString {
            append("\n【记忆】")
            if (memories.isEmpty()) {
                appendLine("目前还没有记住的用户信息。")
            } else {
                appendLine("以下是此前对话中记住的用户信息，回答时自然地运用，不要无故复述：")
                append(memoryContext.format(memories))
            }
            // 保存这件事已经由 MemoryExtractor 在每轮结束后自动完成，模型不必也不应为此多走一轮工具调用
            // ——那正是过去「不明确说记住就记不下来」的原因。这里只留下模型独有的能力：
            // 用户明确要求忘记时删除（自动抽取只会新增，永远不会替用户决定删掉什么）。
            append("""用户明确要求忘记某件事，或表示某条记忆已过时，用 {"tool":"forget","query":"关键词"} 删除。""")
            appendLine("保存新记忆由系统自动完成，你不需要、也不要主动输出保存类的工具调用。")
            append("需要回忆过往对话的具体内容（如「上次聊的那个方案」「之前让你写的东西」）时，")
            append("""用 {"tool":"recall_history","query":"关键词（可空格分隔多个）"} 检索本机历史会话记录，""")
            appendLine("命中的片段会回传给你；没找到就换更短或不同的关键词再试。")
        }
    }

    /**
     * Tells the user what was just remembered, as a system line in the conversation.
     *
     * Automatic capture without this is the worst of both worlds: the assistant quietly builds a
     * profile the user can't see and never agreed to any particular entry of. Both ChatGPT and
     * Gemini surface the write at the moment it happens for exactly this reason — the notice is
     * what makes silent memory acceptable rather than unsettling, and it's also how the user learns
     * that a wrong fact exists in time to go delete it.
     */
    private suspend fun noteMemoryChanges(changes: List<MemoryChange>) {
        if (changes.isEmpty()) return
        val body = changes.joinToString("\n") { change ->
            val verb = if (change.updated) "已更新" else "已记住"
            "🧠 $verb：${change.content}"
        }
        runCatching { chatRepository.add(Sender.SYSTEM, body, ConversationMode.ASK) }
    }

    private suspend fun remember(query: String?): String {
        val fact = query?.trim().orEmpty()
        if (fact.isEmpty()) return "remember 需要 query 里给出要记住的一句话。"
        return when (memoryRepository.add(fact)) {
            MemoryWrite.ADDED -> "已记住：$fact"
            MemoryWrite.REFINED -> "已更新为更具体的说法：$fact"
            MemoryWrite.DUPLICATE -> "这条信息已在记忆中。"
            MemoryWrite.REJECTED -> "这条内容不能存入记忆（密码、验证码、证件号等敏感信息一律不保存）。"
        }
    }

    private suspend fun forget(query: String?): String {
        val keyword = query?.trim().orEmpty()
        if (keyword.isEmpty()) return "forget 需要 query 里给出要忘记的关键词。"
        val removed = memoryRepository.forget(keyword)
        return if (removed.isEmpty()) {
            "没有找到与「$keyword」匹配的记忆。"
        } else {
            "已删除记忆：\n" + removed.joinToString("\n") { "- $it" }
        }
    }

    /** Keyword recall over the saved on-device conversation history (Gemini「参考历史对话」式). */
    private suspend fun recallHistory(query: String?): String {
        val keyword = query?.trim().orEmpty()
        if (keyword.isEmpty()) return "recall_history 需要 query 里给出检索关键词。"
        val hits = chatRepository.searchHistory(keyword, limit = MAX_RECALL_HITS)
        if (hits.isEmpty()) return "历史会话中没有找到与「$keyword」匹配的内容，可换更短或不同的关键词再试。"
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return buildString {
            appendLine("与「$keyword」相关的历史对话片段（新→旧）：")
            hits.forEach { hit ->
                val who = if (hit.sender == Sender.USER.name) "用户" else "AI"
                val where = hit.title?.takeIf { it.isNotBlank() }?.let { "《${it.take(20)}》" } ?: ""
                val excerpt = hit.text.replace('\n', ' ').take(MAX_RECALL_CHARS)
                appendLine("[${dateFormat.format(java.util.Date(hit.timestamp))}$where] $who：$excerpt")
            }
        }.trim()
    }

    private fun toolResultPrompt(result: String): String =
        "【工具结果】\n$result\n\n请基于以上结果继续：若还需要其它工具，再单独输出一行工具 JSON；否则直接给出最终回答（不要提及工具调用过程）。"

    private fun sumTokens(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> a + b
    }

    /**
     * 问答「拼凑」（双模型）：开启且带图片时，先用独立的 [SettingsRepository.chatVisionModel] 把每张图
     * 转成文字描述，拼进问题文本，返回 (增强后的问题, 空图片列表)——于是纯文本问答模型也能"看图"。
     * 未开启 / 无图 / 无视觉模型 / 描述失败时按原样返回（退回直接把图片交给问答模型）。视觉描述这一步的
     * token 属内部辅助步骤，不计入回答用量。
     */

    private suspend fun resolveImages(
        question: String,
        imageDataUrls: List<String>,
    ): Pair<String, List<String>> {
        if (imageDataUrls.isEmpty()) return question to imageDataUrls
        if (!settingsRepository.chatDualModelEnabled.first()) return question to imageDataUrls
        val vision = settingsRepository.chatVisionModel.first() ?: return question to imageDataUrls
        val provider = providerRepository.resolve(vision.providerId) ?: return question to imageDataUrls

        val descriptions = imageDataUrls.mapIndexedNotNull { i, url ->
            val messages = listOf(
                ChatMessageDto(role = "system", content = listOf(ContentPart.text(IMAGE_DESC_PROMPT))),
                ChatMessageDto(
                    role = "user",
                    content = listOf(ContentPart.text("请描述这张图片。"), ContentPart.image(url)),
                ),
            )
            llmClient.chat(provider.baseUrl, provider.apiKey, vision.modelId, messages, provider.protocol)
                .getOrNull()?.content?.takeIf { it.isNotBlank() }
                ?.let { if (imageDataUrls.size > 1) "图片${i + 1}：$it" else it }
        }
        if (descriptions.isEmpty()) return question to imageDataUrls

        val enriched = buildString {
            append(question)
            if (question.isNotBlank()) append("\n\n")
            append("【图片内容】(由视觉模型据图描述，本轮没有原始图片，请以此为准)\n")
            append(descriptions.joinToString("\n\n"))
        }
        return enriched to emptyList()
    }

    /** Builds the system + history + user messages for one Q&A turn (shared by streaming and not). */
    private fun buildMessages(
        modelId: String,
        memorySection: String,
        question: String,
        history: List<UiMessage>,
        imageDataUrls: List<String>,
    ): List<ChatMessageDto> = buildList {
        add(ChatMessageDto(role = "system", content = listOf(ContentPart.text(systemPrompt(modelId, memorySection)))))
        history.takeLast(12).forEach { msg ->
            val role = when (msg.sender) {
                Sender.USER -> "user"
                Sender.AGENT -> "assistant"
                else -> return@forEach // skip system / tool log lines
            }
            if (msg.text.isNotBlank()) {
                add(ChatMessageDto(role = role, content = listOf(ContentPart.text(msg.text))))
            }
        }
        val userParts = buildList {
            if (question.isNotBlank()) add(ContentPart.text(question))
            imageDataUrls.forEach { add(ContentPart.image(it)) }
        }.ifEmpty { listOf(ContentPart.text(question)) }
        add(ChatMessageDto(role = "user", content = userParts))
    }

    private companion object {
        /** System prompt for the 问答 双模型 vision step: objectively describe the user's image. */
        const val IMAGE_DESC_PROMPT =
            "你是图片理解助手。请客观、详尽地描述这张图片的内容（文字、物体、场景、图表数据、界面元素等），" +
                "供另一个模型据此回答用户的问题。只做描述，不要臆测，也不要直接回答用户可能的问题。"

        /** Free info tools 问答 may invoke (read-only; device control stays agent-only). */
        val TOOLS = setOf(
            "weather", "news", "exchange_rate", "crypto", "stock", "wiki", "holiday", "route",
            "music", "lyrics", "ip", "time", "air_quality", "book", "dict", "gold",
            "web_search", "cone_search", "search", "fetch", "fetch_url", "index_url", "index",
        )

        /**
         * 系统接口工具：问答可直接调用的一次性 intent 直达（经 [ActionExecutor]），可逆或需用户在
         * 对方界面再确认；屏幕自动化不在此列（走 agent_task 转智能体）。
         */
        val SYSTEM_TOOLS: Map<String, ActionType> = mapOf(
            "alarm" to ActionType.ALARM,
            "flashlight" to ActionType.FLASHLIGHT,
            "call" to ActionType.CALL,
            "sms" to ActionType.SMS,
            "email" to ActionType.EMAIL,
            "navigate" to ActionType.NAVIGATE,
            "play_music" to ActionType.PLAY_MUSIC,
            "shopping" to ActionType.SHOPPING,
            "share" to ActionType.SHARE,
            "open_app" to ActionType.OPEN_APP,
            "open_settings" to ActionType.OPEN_SETTINGS,
        )

        /** Hand the request over to the agent — honoured only when the caller supplies a handler. */
        const val AGENT_TOOL = "agent_task"

        /** Memory tools — recognised only while memory is enabled and the session isn't incognito. */
        val MEMORY_TOOLS = setOf("remember", "forget", "recall_history")

        /** recall_history result bounds: hits per lookup and characters per excerpt. */
        const val MAX_RECALL_HITS = 10
        const val MAX_RECALL_CHARS = 160

        /** A tool call is a single short JSON line; cap the hold-back so real answers stream. */
        const val MAX_TOOL_JSON_CHARS = 400

        const val MCP_TOOL = "mcp_call"

        /** At most this many consecutive tool rounds before the model must answer. */
        const val MAX_TOOL_ROUNDS = 3
    }
}
