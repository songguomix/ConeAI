package com.cone.agent.agent

import com.cone.agent.data.remote.LlmClient
import com.cone.agent.data.remote.dto.ChatMessageDto
import com.cone.agent.data.remote.dto.ContentPart
import com.cone.agent.data.repository.MemoryCategory
import com.cone.agent.data.repository.MemoryMatcher
import com.cone.agent.data.repository.MemoryWrite
import com.cone.agent.data.repository.MemoryRepository
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One memory written by a turn, for the "记忆已更新" notice the user sees. */
data class MemoryChange(val content: String, val updated: Boolean)

/**
 * Saves what's worth remembering **without the user having to ask**.
 *
 * The `remember` tool it replaces only fired when the model chose to emit it, which meant memory in
 * practice depended on saying "记住…" out loud. This runs on its own after every Q&A turn instead:
 * the answer has already been delivered, so the extra call costs the user no waiting, and a turn
 * that says something durable about them gets saved whether or not the answering model cooperated.
 *
 * Three things keep it cheap and quiet:
 *  - a local pre-filter ([looksPersonal]) drops the turns that obviously carry no fact, so ordinary
 *    questions never trigger a second API call;
 *  - the extractor sees the existing memories and is told to answer `[]` when nothing is new, so
 *    facts don't pile up in near-duplicate phrasings;
 *  - it is fire-and-forget — any failure leaves memory exactly as it was, and the user never sees
 *    an error for a step they didn't ask for.
 */
@Singleton
class MemoryExtractor @Inject constructor(
    private val llmClient: LlmClient,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
    private val memoryRepository: MemoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Considers one completed exchange for storage. Returns immediately; extraction continues in the
     * background. [incognito] turns are never examined, matching the promise that incognito leaves
     * no trace.
     *
     * [onSaved] reports what was actually written, so the UI can tell the user — capture that
     * happens without being asked has to be visible, or the user has no idea what the assistant now
     * knows about them. It fires on a background thread and only when something changed.
     */
    fun observe(
        userMessage: String,
        assistantReply: String,
        incognito: Boolean,
        onSaved: suspend (List<MemoryChange>) -> Unit = {},
    ) {
        if (incognito) return
        val text = userMessage.trim()
        if (text.length < MIN_CHARS || text.length > MAX_CHARS) return
        if (!looksPersonal(text)) return
        scope.launch {
            val changes = runCatching { extract(text, assistantReply) }.getOrDefault(emptyList())
            if (changes.isNotEmpty()) runCatching { onSaved(changes) }
        }
    }

    private suspend fun extract(userMessage: String, assistantReply: String): List<MemoryChange> {
        if (!memoryRepository.enabled.first()) return emptyList()
        val selected = settingsRepository.chatModel.first()
            ?: settingsRepository.selectedModel.first()
            ?: return emptyList()
        val provider = providerRepository.resolve(selected.providerId) ?: return emptyList()

        // Existing facts are listed **with their ids** so the model can point at the one a new
        // statement makes obsolete, instead of only ever adding another line beside it.
        //
        // Only the plausibly-conflicting ones are sent, not the whole store. Every personal turn
        // pays for this call, and shipping eighty facts to decide whether one sentence is new is
        // most of that bill. A fact a statement could contradict is by definition about the same
        // subject, so topical neighbours are exactly the right shortlist — plus 身份, which is what
        // gets superseded in practice (moving city, changing job, a new name).
        val existing = conflictCandidates(memoryRepository.memories.first(), userMessage)
        val messages = listOf(
            ChatMessageDto(role = "system", content = listOf(ContentPart.text(SYSTEM_PROMPT))),
            ChatMessageDto(
                role = "user",
                content = listOf(ContentPart.text(buildString {
                    if (existing.isNotEmpty()) {
                        appendLine("【已记住】(id | 分类 | 内容)")
                        existing.forEach { appendLine("${it.id} | ${it.category} | ${it.content}") }
                        appendLine()
                    }
                    appendLine("【用户说】")
                    appendLine(userMessage.take(MAX_CHARS))
                    if (assistantReply.isNotBlank()) {
                        appendLine()
                        appendLine("【助手答】(仅供理解上文，不要从这里提取事实)")
                        appendLine(assistantReply.take(REPLY_CONTEXT_CHARS))
                    }
                })),
            ),
        )

        val reply = llmClient.chat(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = selected.modelId,
            messages = messages,
            protocol = provider.protocol,
            temperature = 0.0,
            maxTokens = 300,
        ).getOrNull()?.content ?: return emptyList()

        val knownIds = existing.map { it.id }.toSet()
        val saved = mutableListOf<MemoryChange>()
        parseFacts(reply).take(MAX_PER_TURN).forEach { fact ->
            val replaces = fact.replaces?.takeIf { it in knownIds }
            if (replaces != null) {
                if (memoryRepository.supersede(replaces, fact.text, fact.category)) {
                    saved += MemoryChange(fact.text, updated = true)
                }
            } else {
                when (memoryRepository.add(fact.text, fact.category)) {
                    MemoryWrite.ADDED -> saved += MemoryChange(fact.text, updated = false)
                    MemoryWrite.REFINED -> saved += MemoryChange(fact.text, updated = true)
                    // Already known, or something that must not be stored: nothing to tell the user.
                    MemoryWrite.DUPLICATE, MemoryWrite.REJECTED -> Unit
                }
            }
        }
        return saved
    }

    /** The subset of [all] worth showing the extractor for [userMessage]. */
    private fun conflictCandidates(all: List<com.cone.agent.data.repository.MemoryItem>, userMessage: String) =
        if (all.size <= MAX_CONTEXT_MEMORIES) {
            all
        } else {
            val (identity, rest) = all.partition { it.categoryEnum == MemoryCategory.IDENTITY }
            val budget = (MAX_CONTEXT_MEMORIES - identity.size).coerceAtLeast(0)
            identity + rest
                .sortedByDescending { MemoryMatcher.coverage(userMessage, it.content) }
                .take(budget)
        }

    /**
     * Cheap gate deciding whether a turn is even worth an extraction call. Durable facts are almost
     * always stated in the first person ("我是…", "我不吃…", "my daughter…"), so a turn without any
     * self-reference — the overwhelming majority, being plain questions and task instructions — is
     * skipped outright. An explicit "记住…" always passes, whatever else it looks like.
     */
    private fun looksPersonal(text: String): Boolean {
        val lower = text.lowercase()
        if (EXPLICIT_MARKERS.any { lower.contains(it) }) return true
        // 「我」 is in most Chinese requests — 帮我查天气, 我想知道… — so a first-person marker alone
        // sent nearly every turn to a second model call that then correctly answered "[]". A turn
        // shaped like a request and carrying no statement about the user is skipped outright; one
        // that discloses something still passes, including mid-request ("帮我推荐菜，我对花生过敏").
        // The length bound is the safety valve: errands are short, and a longer message is likely
        // to be carrying context worth keeping even when it also asks for something.
        val pureRequest = text.length <= MAX_SKIP_CHARS &&
            REQUEST_SHAPES.any { lower.contains(it) } &&
            DECLARATIVE.none { lower.contains(it) }
        if (pureRequest) return false
        return FIRST_PERSON.any { marker ->
            if (marker.any { it.code > 0x7F }) {
                lower.contains(marker)
            } else {
                Regex("\\b${Regex.escape(marker)}\\b").containsMatchIn(lower)
            }
        }
    }

    /** One fact the extractor proposed, with the memory it supersedes (if any). */
    private data class ExtractedFact(
        val text: String,
        val category: MemoryCategory,
        val replaces: Long?,
    )

    /** Reads the model's JSON array of facts, tolerating fences and stray prose around it. */
    private fun parseFacts(raw: String): List<ExtractedFact> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw.substring(start, end + 1))
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val text = obj.optString("text").trim()
                if (text.length !in MIN_FACT_CHARS..MAX_FACT_CHARS) return@mapNotNull null
                ExtractedFact(
                    text = text,
                    category = MemoryCategory.from(obj.optString("category")),
                    replaces = obj.optLong("replaces", 0L).takeIf { it > 0L },
                )
            }
        }.getOrDefault(emptyList())
            // The prompt forbids secrets, but a model that ignores it must not be the reason a
            // password ends up on disk and in every later prompt. The check itself lives in
            // MemoryRepository so the `remember` tool is covered by the same rule; filtering here
            // as well only avoids a pointless write attempt and a misleading "已记住" notice.
            .filterNot { fact -> memoryRepository.isSensitive(fact.text) }
    }

    private companion object {
        val SYSTEM_PROMPT = """
你是记忆提取器。从【用户说】中找出值得长期记住的、关于用户本人的稳定事实。

只输出一个 JSON 数组，不要任何解释或代码块。没有值得记的就输出 []。
每个元素格式：{"text":"事实", "category":"分类", "replaces":被取代的记忆id或省略}

分类取值：
- identity     姓名称呼、身份职业、居住城市
- preference   喜好厌恶、忌口、风格偏好
- relationship 家人同事宠物等关系
- habit        作息、常用工具、固定安排
- goal         目标、计划、待办的长期事项
- other        以上都不是

replaces 的用法（重要）：新事实与【已记住】里某条**互相矛盾或是它的更新版本**时，填那条的 id，
表示替换而不是新增。例如已记住「用户住在北京」，用户说「我上个月搬到上海了」，
就输出 {"text":"用户住在上海","category":"identity","replaces":该条id}。
只是补充、并不冲突的信息不要填 replaces。

该记：姓名与称呼、身份职业、长期偏好与忌口、家庭成员、居住地、长期目标与习惯、重要纪念日。
不该记：一次性的问题或请求、临时状态（今天累了）、时事与常识、助手说的话、任何密码/验证码/证件号/银行卡号等敏感信息。

每条写成简洁的第三人称陈述句，一句话一个事实，不超过 30 字。
与【已记住】重复或仅换种说法的，不要再输出。

示例：
用户说「我对花生过敏，你推荐菜的时候注意下」 → [{"text":"用户对花生过敏","category":"preference"}]
用户说「帮我查下明天北京天气」 → []
""".trim()

        /** Self-reference markers; a turn with none of these is skipped without an API call. */
        val FIRST_PERSON = listOf(
            "我", "俺", "咱", "本人", "my", "i'm", "i am", "i've", "mine", "me",
        )

        /** Always extract, even without a first-person marker. */
        val EXPLICIT_MARKERS = listOf("记住", "别忘", "记一下", "remember that", "note that")

        /** Phrasings that make a turn an errand rather than a disclosure. */
        val REQUEST_SHAPES = listOf(
            "帮我", "帮忙", "给我", "替我", "我想知道", "我要查", "我想查", "查一下", "搜一下",
            "翻译", "总结一下", "解释一下", "怎么办", "怎么做", "是什么", "为什么",
            "help me", "can you", "could you", "please ", "what is", "what's", "how do i", "how to",
        )

        /**
         * Statement-shaped markers that keep a turn eligible even when it also asks for something —
         * 「我今年32岁，帮我算一下退休年份」 is an errand *and* a disclosure, and the disclosure wins.
         */
        val DECLARATIVE = listOf(
            "我是", "我叫", "我的", "我在", "我住", "我家", "我们家", "我不吃", "我不喝", "我不能",
            "我喜欢", "我爱", "我讨厌", "我对", "我有", "我做", "我从事", "我目前", "我平时", "我通常",
            "我打算", "我计划", "我正在", "我已经", "我过敏", "我今年", "我明年", "我下周", "我下个月",
            "我要去", "我准备", "我养", "我学", "我毕业", "我出生", "我结婚", "我搬",
            "i am", "i'm", "my ", "i like", "i love", "i hate", "i work", "i live", "i have",
            "i prefer", "i'm allergic", "i am allergic",
        )

        const val MIN_CHARS = 4
        const val MAX_CHARS = 1_000
        const val REPLY_CONTEXT_CHARS = 400
        const val MIN_FACT_CHARS = 2
        const val MAX_FACT_CHARS = 60
        const val MAX_PER_TURN = 3

        /** How many existing facts the extraction prompt carries. */
        const val MAX_CONTEXT_MEMORIES = 20

        /** Above this length a turn is never dismissed as a pure errand. */
        const val MAX_SKIP_CHARS = 40
    }
}
