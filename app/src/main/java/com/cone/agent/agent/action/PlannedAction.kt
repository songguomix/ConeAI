package com.cone.agent.agent.action

import android.content.Context
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The catalogue of actions the agent may emit. */
enum class ActionType(val wire: String) {
    OPEN_APP("open_app"),
    CLICK("click"),
    DOUBLE_CLICK("double_click"),
    LONG_CLICK("long_click"),
    INPUT_TEXT("input_text"),
    SWIPE("swipe"),
    SCROLL("scroll"),
    BACK("back"),
    HOME("home"),
    RECENT_APPS("recent_apps"),
    CAPTURE_SCREEN("capture_screen"),
    WEB_SEARCH("web_search"),
    OPEN_URL("open_url"),
    OPEN_BROWSER("open_browser"),
    WEATHER("weather"),
    NEWS("news"),
    EXCHANGE_RATE("exchange_rate"),
    CRYPTO("crypto"),
    STOCK("stock"),
    WIKI("wiki"),
    HOLIDAY("holiday"),
    ROUTE("route"),
    MUSIC("music"),
    LYRICS("lyrics"),
    IP_INFO("ip"),
    WORLD_TIME("time"),
    AIR_QUALITY("air_quality"),
    BOOK("book"),
    DICT("dict"),
    GOLD("gold"),
    NAVIGATE("navigate"),
    PLAY_MUSIC("play_music"),
    SHOPPING("shopping"),
    SHARE("share"),
    CALL("call"),
    SMS("sms"),
    EMAIL("email"),
    ALARM("alarm"),
    OPEN_SETTINGS("open_settings"),
    FLASHLIGHT("flashlight"),
    REQUEST_SCREEN("request_screen"),
    MCP_CALL("mcp_call"),
    WAIT("wait"),
    FINISH("finish"),
    UNKNOWN("unknown");

    companion object {
        fun from(value: String?): ActionType =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN

        /**
         * 无头动作：既不看真实屏幕也不动真实屏幕（信息查询 API、闹钟、手电筒、等待、完成…）。
         * 语音助手悬浮窗敞开时这些动作照常执行、胶囊不必退出；其余动作需要真实屏幕，
         * 第一个到来时胶囊让位（见 AgentController 的 usingScreen 交接）。
         */
        val HEADLESS: Set<ActionType> = setOf(
            WEATHER, NEWS, EXCHANGE_RATE, CRYPTO, STOCK, WIKI, HOLIDAY, ROUTE,
            MUSIC, LYRICS, IP_INFO, WORLD_TIME, AIR_QUALITY, BOOK, DICT, GOLD,
            MCP_CALL, ALARM, FLASHLIGHT, WAIT, CAPTURE_SCREEN, FINISH, UNKNOWN,
            // 授权弹窗是系统级对话框，浮在一切之上，胶囊无需让位。
            REQUEST_SCREEN,
        )
    }
}

/** A fully parsed decision returned by the model for a single step. */
data class PlannedAction(
    val thought: String,
    val type: ActionType,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val app: String? = null,
    val direction: String? = null,
    val durationMs: Long? = null,
    val waitMs: Long? = null,
    val done: Boolean = false,
    val summary: String = "",
    val plan: List<String> = emptyList(),
) {
    /** Human readable one-liner for the floating control center + logs, in the selected app language. */
    fun describe(context: Context): String = when (type) {
        ActionType.OPEN_APP -> LocaleHelper.string(context, R.string.act_open_app, app.orEmpty())
        ActionType.CLICK -> LocaleHelper.string(context, R.string.act_click, x ?: 0, y ?: 0)
        ActionType.DOUBLE_CLICK -> LocaleHelper.string(context, R.string.act_double_click, x ?: 0, y ?: 0)
        ActionType.LONG_CLICK -> LocaleHelper.string(context, R.string.act_long_click, x ?: 0, y ?: 0)
        ActionType.INPUT_TEXT -> LocaleHelper.string(context, R.string.act_input, text.orEmpty().take(30))
        ActionType.SWIPE -> LocaleHelper.string(context, R.string.act_swipe, x ?: 0, y ?: 0, x2 ?: 0, y2 ?: 0)
        ActionType.SCROLL -> LocaleHelper.string(context, R.string.act_scroll, direction.orEmpty())
        ActionType.BACK -> LocaleHelper.string(context, R.string.cd_back)
        ActionType.HOME -> LocaleHelper.string(context, R.string.act_home)
        ActionType.RECENT_APPS -> LocaleHelper.string(context, R.string.act_recent)
        ActionType.CAPTURE_SCREEN -> LocaleHelper.string(context, R.string.act_capture)
        ActionType.WEB_SEARCH -> LocaleHelper.string(context, R.string.act_web_search, text.orEmpty().take(40))
        ActionType.OPEN_URL -> LocaleHelper.string(context, R.string.act_open_url, text.orEmpty().take(60))
        ActionType.OPEN_BROWSER -> LocaleHelper.string(context, R.string.act_open_browser, text.orEmpty().take(40))
        ActionType.WEATHER -> LocaleHelper.string(context, R.string.act_weather, text.orEmpty().take(30))
        ActionType.NEWS -> LocaleHelper.string(context, R.string.act_news, text.orEmpty().take(30))
        ActionType.EXCHANGE_RATE -> LocaleHelper.string(context, R.string.act_exchange, text.orEmpty().take(30))
        ActionType.CRYPTO -> LocaleHelper.string(context, R.string.act_crypto, text.orEmpty().take(30))
        ActionType.STOCK -> LocaleHelper.string(context, R.string.act_stock, text.orEmpty().take(30))
        ActionType.WIKI -> LocaleHelper.string(context, R.string.act_wiki, text.orEmpty().take(30))
        ActionType.HOLIDAY -> LocaleHelper.string(context, R.string.act_holiday, text.orEmpty().take(30))
        ActionType.ROUTE -> LocaleHelper.string(context, R.string.act_route, text.orEmpty().take(40))
        ActionType.MUSIC -> LocaleHelper.string(context, R.string.act_music, text.orEmpty().take(30))
        ActionType.LYRICS -> LocaleHelper.string(context, R.string.act_lyrics, text.orEmpty().take(30))
        ActionType.IP_INFO -> LocaleHelper.string(context, R.string.act_ip)
        ActionType.WORLD_TIME -> LocaleHelper.string(context, R.string.act_time, text.orEmpty().take(30))
        ActionType.AIR_QUALITY -> LocaleHelper.string(context, R.string.act_air, text.orEmpty().take(30))
        ActionType.BOOK -> LocaleHelper.string(context, R.string.act_book, text.orEmpty().take(30))
        ActionType.DICT -> LocaleHelper.string(context, R.string.act_dict, text.orEmpty().take(30))
        ActionType.GOLD -> LocaleHelper.string(context, R.string.act_gold, text.orEmpty().take(20))
        ActionType.CALL -> LocaleHelper.string(context, R.string.act_call, text.orEmpty().take(20))
        ActionType.SMS -> LocaleHelper.string(context, R.string.act_sms, app.orEmpty().take(20), text.orEmpty().take(30))
        ActionType.EMAIL -> LocaleHelper.string(context, R.string.act_email, app.orEmpty().take(30), text.orEmpty().take(30))
        ActionType.ALARM -> LocaleHelper.string(context, R.string.act_alarm, text.orEmpty().take(30))
        ActionType.OPEN_SETTINGS -> LocaleHelper.string(context, R.string.act_open_settings, text.orEmpty().take(20))
        ActionType.FLASHLIGHT -> LocaleHelper.string(context, R.string.act_flashlight, text.orEmpty().take(10))
        ActionType.REQUEST_SCREEN -> LocaleHelper.string(context, R.string.act_request_screen)
        ActionType.NAVIGATE -> LocaleHelper.string(context, R.string.act_navigate, text.orEmpty().take(30))
        ActionType.PLAY_MUSIC -> LocaleHelper.string(
            context,
            R.string.act_play_music,
            app?.takeIf { it.isNotBlank() } ?: LocaleHelper.string(context, R.string.act_default_app),
            text.orEmpty().take(30),
        )
        ActionType.SHOPPING -> LocaleHelper.string(
            context,
            R.string.act_shopping,
            app?.takeIf { it.isNotBlank() } ?: LocaleHelper.string(context, R.string.act_default_app),
            text.orEmpty().take(30),
        )
        ActionType.SHARE -> LocaleHelper.string(
            context,
            R.string.act_share,
            app?.takeIf { it.isNotBlank() } ?: LocaleHelper.string(context, R.string.act_share_target_default),
            text.orEmpty().take(30),
        )
        ActionType.MCP_CALL -> LocaleHelper.string(context, R.string.act_unknown)
        ActionType.WAIT -> LocaleHelper.string(context, R.string.act_wait, waitMs ?: 0L)
        ActionType.FINISH -> LocaleHelper.string(context, R.string.act_finish)
        ActionType.UNKNOWN -> LocaleHelper.string(context, R.string.act_unknown)
    }
}

/**
 * A batch of actions the model planned from a *single* observation, plus the task-level metadata
 * (overall reasoning, the high-level plan, and whether the whole task is finished). The agent
 * executes [actions] in order — smoothly, without re-observing between them — then re-observes only
 * if the task is not yet [done].
 */
data class ActionPlan(
    val thought: String,
    val actions: List<PlannedAction>,
    val done: Boolean = false,
    val summary: String = "",
    val plan: List<String> = emptyList(),
)

@Serializable
private data class AgentResponseDto(
    val thought: String = "",
    // New batch format: every action to perform from this one screenshot, in order.
    val actions: List<ActionItemDto> = emptyList(),
    // Backward-compatible single-action fields (used only when "actions" is absent).
    val action: String = "",
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val app: String? = null,
    val direction: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val ms: Long? = null,
    val done: Boolean = false,
    val summary: String = "",
    val plan: List<String> = emptyList(),
)

/** One entry inside the [AgentResponseDto.actions] array. */
@Serializable
private data class ActionItemDto(
    val action: String = "",
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val app: String? = null,
    val direction: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val ms: Long? = null,
    val thought: String = "",
)

/** Parses a model response (possibly wrapped in prose / fences) into an [ActionPlan]. */
object ActionParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Decodes one streamed element of the response's actions array (see [StreamingPlanParser]). */
    internal fun parseItem(raw: String): PlannedAction =
        json.decodeFromString(ActionItemDto.serializer(), raw).toPlannedAction()

    /** Decodes a JSON array of strings (the streamed plan outline). */
    internal fun parseStringList(raw: String): List<String> =
        json.decodeFromString(ListSerializer(String.serializer()), raw)

    /** Decodes a JSON string literal (quotes + escapes included) into its value. */
    internal fun parseStringLiteral(raw: String): String =
        json.decodeFromString(String.serializer(), raw)

    fun parse(raw: String): Result<ActionPlan> = runCatching {
        val jsonText = extractJson(raw) ?: error("模型未返回有效 JSON：${raw.take(120)}")
        val dto = json.decodeFromString(AgentResponseDto.serializer(), jsonText)
        // Prefer the new batch format; fall back to a single top-level action for compatibility.
        val actions = if (dto.actions.isNotEmpty()) {
            dto.actions.map { it.toPlannedAction() }
        } else if (dto.action.isNotBlank()) {
            listOf(dto.toSingleAction())
        } else {
            emptyList()
        }
        ActionPlan(
            thought = dto.thought,
            actions = actions,
            done = dto.done || actions.any { it.type == ActionType.FINISH },
            summary = dto.summary,
            plan = dto.plan,
        )
    }

    private fun ActionItemDto.toPlannedAction(): PlannedAction = PlannedAction(
        thought = thought,
        type = ActionType.from(action),
        x = x,
        y = y,
        x2 = x2,
        y2 = y2,
        text = text,
        app = app,
        direction = direction,
        durationMs = durationMs,
        waitMs = ms,
        done = ActionType.from(action) == ActionType.FINISH,
    )

    private fun AgentResponseDto.toSingleAction(): PlannedAction = PlannedAction(
        thought = thought,
        type = ActionType.from(action),
        x = x,
        y = y,
        x2 = x2,
        y2 = y2,
        text = text,
        app = app,
        direction = direction,
        durationMs = durationMs,
        waitMs = ms,
        done = done || ActionType.from(action) == ActionType.FINISH,
        summary = summary,
    )

    /** Extracts the first balanced JSON object from arbitrary text. */
    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
