package com.cone.agent.code

import android.content.Context
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.remote.LlmClient
import com.cone.agent.data.remote.dto.ChatMessageDto
import com.cone.agent.data.remote.dto.ContentPart
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** One project the model proposes: a name, why it is worth building, and the brief to build it from. */
data class ProjectIdea(
    val title: String,
    val summary: String,
    val template: ProjectTemplate,
    val brief: String,
)

/**
 * Asks the selected model what is worth building next.
 *
 * The home screen opens on a blank input otherwise, and "think of a project" is the hardest part of
 * starting one. The reply is a pipe-delimited line per idea rather than JSON — the same reasoning as
 * everywhere else in this app: it has to parse from any model the user plugs in, including small
 * local ones with no reliable JSON mode, and a malformed line costs one idea instead of all four.
 */
@Singleton
class CodeIdeas @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmClient: LlmClient,
    private val providerRepository: ProviderRepository,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun suggest(existing: List<String>): Result<List<ProjectIdea>> {
        val selected = settingsRepository.chatModel.first() ?: settingsRepository.selectedModel.first()
            ?: return Result.failure(IllegalStateException(LocaleHelper.string(context, R.string.code_err_no_model)))
        val provider = providerRepository.resolve(selected.providerId)
            ?: return Result.failure(IllegalStateException(LocaleHelper.string(context, R.string.code_err_no_provider)))

        val reply = llmClient.chat(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = selected.modelId,
            messages = listOf(
                ChatMessageDto("system", listOf(ContentPart.text(prompt(existing)))),
                ChatMessageDto("user", listOf(ContentPart.text("请给出 $COUNT 个项目建议。"))),
            ),
            protocol = provider.protocol,
            // High enough that pulling to refresh actually produces different ideas.
            temperature = 1.0,
            maxTokens = 700,
        ).getOrElse { return Result.failure(it) }

        val ideas = parse(reply.content)
        return if (ideas.isEmpty()) {
            Result.failure(IllegalStateException(LocaleHelper.string(context, R.string.code_err_ideas)))
        } else {
            Result.success(ideas)
        }
    }

    private fun parse(reply: String): List<ProjectIdea> = reply.lines()
        .mapNotNull { raw ->
            // Tolerate the decoration models add anyway: bullets, numbering, stray code fences.
            val line = raw.trim().removePrefix("-").removePrefix("*").trim()
                .replace(Regex("^\\d+[.、)]\\s*"), "")
                .removeSurrounding("`")
                .trim()
            if (line.isBlank() || line.startsWith("```")) return@mapNotNull null
            val parts = line.split("|").map { it.trim() }
            if (parts.size < 4) return@mapNotNull null
            val title = parts[0].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProjectIdea(
                title = title.take(MAX_TITLE),
                template = ProjectTemplate.of(parts[1].lowercase()),
                summary = parts[2].take(MAX_SUMMARY),
                brief = parts[3].ifBlank { parts[2] },
            )
        }
        .distinctBy { it.title }
        .take(COUNT)

    private fun prompt(existing: List<String>) = buildString {
        append(
            """
你在为一个「在手机上写代码」的应用出主意：用户打开应用，你要提议几个现在就能动手做、并且做完立刻能用的小项目。

【硬约束】手机上没有终端、编译器和包管理器，代码只能写不能运行——唯一例外是网页项目（HTML/CSS/JS），应用内可以直接打开预览。所以：
- 至少一半建议应该是 web 模板的网页小工具/小游戏，用户做完当场就能玩。
- 其余可以是 python / node / kotlin / blank，但要明说是"写好带到电脑上跑"的那种。
- 不要提议需要 npm install、后端服务、数据库、API Key 或构建工具链的项目。
- 规模要小：一个人在手机上几轮对话内能完成。

【输出格式】每行一个建议，用 | 分成四段，不要写标题、编号、解释或代码块：
项目名|模板|一句话说明它是什么|给编码助手的具体开发要求
其中「模板」只能是 web、python、node、kotlin、blank 之一。

示例（照这个格式，但内容要换成你自己的想法）：
番茄钟|web|可以计时、休息提醒、记录今天完成几轮的番茄钟网页|做一个番茄钟网页：25 分钟工作 / 5 分钟休息可调，圆形进度环，结束时页面提示，完成轮数存在 localStorage。
            """.trim(),
        )
        if (existing.isNotEmpty()) {
            append("\n\n【已有项目】用户已经做过下面这些，请提议不一样的：")
            append(existing.take(MAX_EXISTING).joinToString("、"))
        }
        append("\n")
        append(LocaleHelper.string(context, R.string.output_language_directive))
    }

    private companion object {
        const val COUNT = 4
        const val MAX_TITLE = 24
        const val MAX_SUMMARY = 60
        const val MAX_EXISTING = 12
    }
}
