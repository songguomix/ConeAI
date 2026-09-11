package com.cone.agent.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cone.agent.R
import com.cone.agent.agent.action.ActionType
import com.cone.agent.agent.action.PlannedAction
import com.cone.agent.core.LocaleHelper
import com.cone.agent.ui.BrowserActivity
import com.cone.agent.vision.AccessibilityBridge
import com.cone.agent.web.DailyInfoService
import com.cone.agent.web.SearchEngine
import com.cone.agent.web.SearchEngineResolver
import com.cone.agent.mcp.McpManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of executing one action. [observation] carries free-text the model must *read* next (e.g.
 * weather / news fetched directly); the controller appends it to the task history so the following
 * Think call sees it. Null for actions whose effect is only visible on the screen.
 */
data class ActionResult(val success: Boolean, val message: String, val observation: String? = null)

/** Translates a [PlannedAction] into concrete device operations via the accessibility bridge. */
@Singleton
class ActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: AccessibilityBridge,
    private val searchEngineResolver: SearchEngineResolver,
    private val dailyInfoService: DailyInfoService,
    private val captureManager: com.cone.agent.vision.ScreenCaptureManager,
    private val mcpManager: dagger.Lazy<McpManager>,
) {

    private fun str(id: Int, vararg args: Any) = LocaleHelper.string(context, id, *args)

    suspend fun execute(action: PlannedAction, allowWebSearch: Boolean = true): ActionResult = when (action.type) {
        ActionType.OPEN_APP -> openApp(action.app)
        ActionType.CLICK -> requireXy(action) { x, y ->
            ActionResult(bridge.click(x, y), str(R.string.act_click, x, y))
        }
        ActionType.DOUBLE_CLICK -> requireXy(action) { x, y ->
            ActionResult(bridge.doubleClick(x, y), str(R.string.act_double_click, x, y))
        }
        ActionType.LONG_CLICK -> requireXy(action) { x, y ->
            ActionResult(bridge.longClick(x, y), str(R.string.act_long_click, x, y))
        }
        ActionType.INPUT_TEXT -> {
            val text = action.text
            if (text == null) {
                ActionResult(false, str(R.string.exec_input_missing))
            } else {
                ActionResult(bridge.inputText(text, action.x, action.y), str(R.string.act_input, text.take(30)))
            }
        }
        ActionType.SWIPE -> {
            val x1 = action.x; val y1 = action.y; val x2 = action.x2; val y2 = action.y2
            if (x1 == null || y1 == null || x2 == null || y2 == null) {
                ActionResult(false, str(R.string.exec_swipe_missing))
            } else {
                val ok = bridge.swipe(x1, y1, x2, y2, action.durationMs ?: 300L)
                ActionResult(ok, str(R.string.act_swipe, x1, y1, x2, y2))
            }
        }
        ActionType.SCROLL -> scroll(action.direction)
        ActionType.BACK -> ActionResult(bridge.back(), str(R.string.cd_back))
        ActionType.HOME -> ActionResult(bridge.home(), str(R.string.act_home))
        ActionType.RECENT_APPS -> ActionResult(bridge.recents(), str(R.string.act_recent))
        ActionType.CAPTURE_SCREEN -> ActionResult(true, str(R.string.exec_capture))
        ActionType.WEB_SEARCH -> if (allowWebSearch) webSearch(action.text) else searchDisabled()
        ActionType.OPEN_URL -> readUrl(action.text, allowWebSearch)
        ActionType.OPEN_BROWSER -> openBrowser(action.text, allowWebSearch)
        ActionType.WEATHER -> {
            val info = dailyInfoService.weather(action.text)
            ActionResult(true, str(R.string.exec_weather_done), observation = info)
        }
        ActionType.NEWS -> {
            val info = dailyInfoService.news(action.text)
            ActionResult(true, str(R.string.exec_news_done), observation = info)
        }
        ActionType.EXCHANGE_RATE -> {
            val info = dailyInfoService.exchangeRate(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.CRYPTO -> {
            val info = dailyInfoService.crypto(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.STOCK -> {
            val info = dailyInfoService.stock(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.WIKI -> {
            val info = dailyInfoService.wiki(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.HOLIDAY -> {
            val info = dailyInfoService.holiday(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.ROUTE -> {
            val info = dailyInfoService.route(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.MUSIC -> {
            val info = dailyInfoService.music(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.LYRICS -> {
            val info = dailyInfoService.lyrics(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.IP_INFO -> {
            val info = dailyInfoService.ipInfo()
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.WORLD_TIME -> {
            val info = dailyInfoService.worldTime(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.AIR_QUALITY -> {
            val info = dailyInfoService.airQuality(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.BOOK -> {
            val info = dailyInfoService.book(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.DICT -> {
            val info = dailyInfoService.dict(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.GOLD -> {
            val info = dailyInfoService.gold(action.text)
            ActionResult(true, str(R.string.exec_info_done), observation = info)
        }
        ActionType.NAVIGATE -> navigate(action.text, action.app)
        ActionType.PLAY_MUSIC -> playMusic(action.text, action.app)
        ActionType.SHOPPING -> shopping(action.text, action.app, allowWebSearch)
        ActionType.SHARE -> share(action.text, action.app)
        ActionType.CALL -> call(action.text)
        ActionType.SMS -> sms(action.text, action.app)
        ActionType.EMAIL -> email(action.text, action.app)
        ActionType.ALARM -> alarm(action.text)
        ActionType.OPEN_SETTINGS -> openSettings(action.text)
        ActionType.FLASHLIGHT -> flashlight(action.text)
        ActionType.MCP_CALL -> mcpCall(action.text, action.app)
        ActionType.REQUEST_SCREEN -> requestScreen()
        ActionType.WAIT -> {
            val ms = (action.waitMs ?: 1000L).coerceIn(100L, 10_000L)
            delay(ms)
            ActionResult(true, str(R.string.act_wait, ms))
        }
        ActionType.FINISH -> ActionResult(true, action.summary.ifBlank { str(R.string.agent_status_done) })
        ActionType.UNKNOWN -> ActionResult(false, str(R.string.exec_unknown))
    }

    private inline fun requireXy(
        action: PlannedAction,
        block: (Int, Int) -> ActionResult,
    ): ActionResult {
        val x = action.x; val y = action.y
        return if (x == null || y == null) ActionResult(false, str(R.string.exec_missing_coords)) else block(x, y)
    }

    /**
     * Opens the built-in browser **on screen** at the search results for [query] (engine per user
     * setting / IP region). It's visible so the agent reads the results the same way it reads any
     * app — via the next screenshot / UI tree — then scrolls or taps a result with normal actions.
     * (No hidden fetch: this model decides by looking at the screen.)
     */
    private suspend fun webSearch(query: String?): ActionResult {
        if (query.isNullOrBlank()) return ActionResult(false, str(R.string.exec_search_missing))
        val engine = searchEngineResolver.resolve()
        return launchBrowser(engine.searchUrl(query), engine) { str(R.string.exec_search_opened, query.trim()) }
    }

    private fun searchDisabled() = ActionResult(false, str(R.string.exec_agent_search_disabled))

    /** Opens a concrete page; agent callers cannot fall back to a keyword search. */
    private suspend fun readUrl(url: String?, allowWebSearch: Boolean): ActionResult {
        if (url.isNullOrBlank()) return ActionResult(false, str(R.string.exec_url_missing))
        if (!allowWebSearch) {
            val target = AgentWebPolicy.directUrl(url) ?: return searchDisabled()
            return launchBrowser(target, SearchEngine.BING) { str(R.string.exec_page_opened, target) }
        }
        val engine = searchEngineResolver.resolve()
        return launchBrowser(engine.urlFor(url), engine) { str(R.string.exec_page_opened, url.trim()) }
    }

    /** Opens the built-in browser (home, or a query/url) visibly on screen. */
    private suspend fun openBrowser(queryOrUrl: String?, allowWebSearch: Boolean): ActionResult {
        if (!allowWebSearch) {
            val target = if (queryOrUrl.isNullOrBlank()) "about:blank"
                else AgentWebPolicy.directUrl(queryOrUrl) ?: return searchDisabled()
            return launchBrowser(target, SearchEngine.BING) {
                str(R.string.act_open_browser, queryOrUrl.orEmpty().take(40))
            }
        }
        val engine = searchEngineResolver.resolve()
        return launchBrowser(engine.urlFor(queryOrUrl), engine) {
            str(R.string.act_open_browser, queryOrUrl.orEmpty().take(40))
        }
    }

    private inline fun launchBrowser(
        url: String,
        engine: SearchEngine,
        message: () -> String,
    ): ActionResult {
        val intent = BrowserActivity.intent(context, url, engine.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, message())
        }.getOrElse { ActionResult(false, str(R.string.exec_open_browser_failed, it.message ?: "")) }
    }

    /**
     * Starts navigation to [dest] through a map app's **free keyless URI interface** (the same way
     * system assistants do it): 高德 → 百度 → Google Maps by installation, or the app named in
     * [appQuery]; a generic `geo:` view is the last resort. Turn-by-turn itself is the map app's
     * job — the agent only fires the URI, never drives the map UI by taps.
     */
    private fun navigate(dest: String?, appQuery: String?): ActionResult {
        if (dest.isNullOrBlank()) return ActionResult(false, str(R.string.exec_navigate_missing))
        val d = dest.trim()
        val enc = URLEncoder.encode(d, "UTF-8")
        val candidates = listOf(
            Triple(
                listOf("高德", "amap", "gaode"),
                "com.autonavi.minimap",
                "amapuri://route/plan/?sourceApplication=ConeAI&dname=$enc&dev=0&t=0",
            ),
            Triple(
                listOf("百度", "baidu"),
                "com.baidu.BaiduMap",
                "baidumap://map/direction?destination=$enc&mode=driving&src=ConeAI",
            ),
            Triple(
                listOf("google", "谷歌"),
                "com.google.android.apps.maps",
                "google.navigation:q=$enc",
            ),
        )
        val preferred = appQuery?.takeIf { it.isNotBlank() }?.lowercase()
        val ordered = if (preferred == null) {
            candidates
        } else {
            candidates.sortedByDescending { (names, _, _) -> names.any { preferred.contains(it) } }
        }
        for ((_, pkg, uri) in ordered) {
            if (runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isFailure) continue
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return ActionResult(true, str(R.string.act_navigate, d.take(30)))
            }
        }
        // No known map app — the universal geo: intent lets any installed map take over.
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(geo)
            ActionResult(true, str(R.string.act_navigate, d.take(30)))
        }.getOrElse { ActionResult(false, str(R.string.exec_navigate_failed)) }
    }

    /** Opens the dialer with [number] prefilled（`ACTION_DIAL`，免权限）——拨出那一下永远由用户按。 */
    private fun call(number: String?): ActionResult {
        val num = number?.filter { it.isDigit() || it == '+' }.orEmpty()
        if (num.isBlank()) return ActionResult(false, str(R.string.exec_call_missing))
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, str(R.string.act_call, num))
        }.getOrElse { ActionResult(false, str(R.string.exec_call_failed)) }
    }

    /** Opens the SMS composer prefilled（`smsto:` + sms_body）; the user (or a guarded tap) sends. */
    private fun sms(body: String?, to: String?): ActionResult {
        if (body.isNullOrBlank()) return ActionResult(false, str(R.string.exec_sms_missing))
        val number = to?.filter { it.isDigit() || it == '+' }.orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, str(R.string.act_sms, number, body.take(30)))
        }.getOrElse { ActionResult(false, str(R.string.exec_sms_failed)) }
    }

    /** Opens the email composer prefilled（`mailto:` + body）; sending stays with the user. */
    private fun email(body: String?, to: String?): ActionResult {
        if (body.isNullOrBlank()) return ActionResult(false, str(R.string.exec_email_missing))
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + to?.trim().orEmpty()))
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, str(R.string.act_email, to.orEmpty(), body.take(30)))
        }.getOrElse { ActionResult(false, str(R.string.exec_email_failed)) }
    }

    /**
     * Sets an alarm（"HH:MM 标签"）or a countdown timer（"X分钟/X小时/X秒 标签"）through the standard
     * `AlarmClock` interface with SKIP_UI — the clock app registers it directly, nothing to tap.
     * Both are freely reversible in the clock app, hence no high-risk confirmation.
     */
    private fun alarm(spec: String?): ActionResult {
        val s = spec?.trim().orEmpty()
        if (s.isEmpty()) return ActionResult(false, str(R.string.exec_alarm_missing))
        val clock = Regex("^(\\d{1,2})[:：点时](\\d{1,2})?").find(s)
        val intent: Intent
        if (clock != null) {
            val hour = clock.groupValues[1].toInt()
            val minute = clock.groupValues[2].toIntOrNull() ?: 0
            if (hour > 23 || minute > 59) return ActionResult(false, str(R.string.exec_alarm_missing))
            intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            s.removeRange(clock.range).trim().takeIf { it.isNotBlank() }
                ?.let { intent.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
        } else {
            var seconds = 0
            var stripped = s
            for ((regex, unit) in DURATION_UNITS) {
                regex.find(stripped)?.let { m ->
                    seconds += m.groupValues[1].toInt() * unit
                    stripped = stripped.removeRange(m.range)
                }
            }
            if (seconds <= 0) return ActionResult(false, str(R.string.exec_alarm_missing))
            intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            stripped.trim().takeIf { it.isNotBlank() }
                ?.let { intent.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
        }
        intent.putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, str(R.string.act_alarm, s.take(30)))
        }.getOrElse { ActionResult(false, str(R.string.exec_alarm_failed)) }
    }

    /** Jumps straight to a system-settings page by keyword（wifi/蓝牙/声音…；空 → 设置首页）. */
    private fun openSettings(query: String?): ActionResult {
        val q = query?.trim()?.lowercase().orEmpty()
        val actionName = SETTINGS_PAGES.firstOrNull { (names, _) -> names.any { q.contains(it) } }?.second
            ?: android.provider.Settings.ACTION_SETTINGS
        return runCatching {
            context.startActivity(Intent(actionName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionResult(true, str(R.string.act_open_settings, q.ifBlank { "settings" }))
        }.getOrElse { ActionResult(false, str(R.string.exec_open_settings_failed)) }
    }

    /** Torch on/off through CameraManager（no permission needed for setTorchMode）. */
    private fun flashlight(state: String?): ActionResult {
        val on = when (state?.trim()?.lowercase()) {
            "on", "开", "打开", "true", "1" -> true
            "off", "关", "关闭", "false", "0" -> false
            else -> return ActionResult(false, str(R.string.exec_flashlight_missing))
        }
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionResult(false, str(R.string.exec_flashlight_unavailable))
            cm.setTorchMode(id, on)
            ActionResult(true, str(R.string.act_flashlight, if (on) "on" else "off"))
        }.getOrElse { ActionResult(false, str(R.string.exec_flashlight_unavailable)) }
    }

    /**
     * Plays music through the **standard Android play-from-search intent**（`MediaStore.INTENT_ACTION_
     * MEDIA_PLAY_FROM_SEARCH`，语音助手同款接口）: the music app searches [query] and starts playback
     * itself — no driving its UI tap by tap. [appQuery] pins a specific player（网易云/QQ音乐/Spotify…）;
     * blank lets the system's default handler take it.
     */
    private fun playMusic(query: String?, appQuery: String?): ActionResult {
        if (query.isNullOrBlank()) return ActionResult(false, str(R.string.exec_play_music_missing))
        val q = query.trim()
        fun intent(pkg: String?): Intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, q)
            putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            if (pkg != null) setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackApp = str(R.string.act_default_app)
        val target = appQuery?.takeIf { it.isNotBlank() }?.let { q2 ->
            val lower = q2.trim().lowercase()
            KNOWN_MUSIC_APPS.firstOrNull { (names, _) -> names.any { lower.contains(it) } }?.second
                ?.takeIf { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }
        }
        if (target != null && runCatching { context.startActivity(intent(target)) }.isSuccess) {
            return ActionResult(true, str(R.string.act_play_music, appQuery.orEmpty().ifBlank { fallbackApp }, q.take(30)))
        }
        // Unspecified/unavailable player → let the system route to whichever music app handles it,
        // then try any installed known player explicitly（some ignore the implicit form）.
        if (runCatching { context.startActivity(intent(null)) }.isSuccess) {
            return ActionResult(true, str(R.string.act_play_music, fallbackApp, q.take(30)))
        }
        for ((_, pkg) in KNOWN_MUSIC_APPS) {
            if (runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isFailure) continue
            if (runCatching { context.startActivity(intent(pkg)) }.isSuccess) {
                return ActionResult(true, str(R.string.act_play_music, pkg, q.take(30)))
            }
        }
        return ActionResult(false, str(R.string.exec_play_music_failed))
    }

    /**
     * Jumps straight to a shopping app's search-results page for [query] through its **free URI
     * interface**（淘宝/京东/拼多多）, instead of opening the app and typing into its search box.
     * Q&A may fall back to browser search when no shopping app is installed; agent tasks may not.
     * Only opens the
     * results list — any actual 下单/支付 tap later still goes through the high-risk confirmation.
     */
    private suspend fun shopping(query: String?, appQuery: String?, allowWebSearch: Boolean): ActionResult {
        if (query.isNullOrBlank()) return ActionResult(false, str(R.string.exec_shopping_missing))
        val q = query.trim()
        val enc = URLEncoder.encode(q, "UTF-8")
        val candidates = listOf(
            Triple(listOf("淘宝", "taobao"), "com.taobao.taobao", "taobao://s.taobao.com/search?q=$enc"),
            Triple(
                listOf("京东", "jd", "jingdong"),
                "com.jingdong.app.mall",
                "openApp.jdMobile://virtual?params=%7B%22category%22%3A%22jump%22%2C%22des%22%3A%22productList%22%2C%22keyWord%22%3A%22$enc%22%2C%22from%22%3A%22search%22%7D",
            ),
            Triple(
                listOf("拼多多", "pdd", "pinduoduo"),
                "com.xunmeng.pinduoduo",
                "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=$enc",
            ),
        )
        val preferred = appQuery?.takeIf { it.isNotBlank() }?.lowercase()
        val ordered = if (preferred == null) {
            candidates
        } else {
            candidates.sortedByDescending { (names, _, _) -> names.any { preferred.contains(it) } }
        }
        for ((_, pkg, uri) in ordered) {
            if (runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isFailure) continue
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return ActionResult(true, str(R.string.act_shopping, pkg, q.take(30)))
            }
        }
        // Search fallback is only available to callers that allow it (not agent tasks).
        if (!allowWebSearch) return ActionResult(false, str(R.string.exec_agent_shopping_unavailable))
        val engine = searchEngineResolver.resolve()
        return launchBrowser(engine.searchUrl(q), engine) {
            str(R.string.act_shopping, str(R.string.act_default_app), q.take(30))
        }
    }

    /**
     * Shares [text] through the system share interface (`ACTION_SEND`), the sanctioned way to hand
     * composed text to 微信 / QQ etc.: the target app's own share flow takes over (contact picker +
     * its own send confirmation), so the agent never has to drive the app's UI to type and send.
     * [appQuery] narrows the share to one app (name or package); blank → system chooser.
     */
    private fun share(text: String?, appQuery: String?): ActionResult {
        if (text.isNullOrBlank()) return ActionResult(false, str(R.string.exec_share_missing))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val target = appQuery?.takeIf { it.isNotBlank() }?.let { resolveSharePackage(it.trim()) }
        val intent = if (target != null) {
            send.setPackage(target)
        } else {
            Intent.createChooser(send, str(R.string.exec_share_chooser))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallbackTarget = str(R.string.act_share_target_default)
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, str(R.string.act_share, appQuery?.takeIf { it.isNotBlank() } ?: fallbackTarget, text.take(30)))
        }.getOrElse {
            // The named app can't receive text shares (or is missing) — retry with the open chooser.
            runCatching {
                context.startActivity(
                    Intent.createChooser(send, str(R.string.exec_share_chooser))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                ActionResult(true, str(R.string.act_share, fallbackTarget, text.take(30)))
            }.getOrElse { e -> ActionResult(false, str(R.string.exec_share_failed, e.message ?: "")) }
        }
    }

    /** Well-known messengers by name, then any installed app whose label/package matches. */
    private fun resolveSharePackage(query: String): String? {
        val q = query.lowercase()
        KNOWN_SHARE_TARGETS.firstOrNull { (names, _) -> names.any { q.contains(it) } }
            ?.second
            ?.takeIf { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }
            ?.let { return it }
        val pm = context.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        return apps.firstOrNull { pm.getApplicationLabel(it).toString().equals(query, ignoreCase = true) }
            ?.packageName
            ?: apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true) }
                ?.packageName
            ?: apps.firstOrNull { it.packageName.contains(query, ignoreCase = true) }?.packageName
    }

    private fun openApp(query: String?): ActionResult {
        if (query.isNullOrBlank()) return ActionResult(false, str(R.string.exec_app_missing))
        val intent = resolveLaunchIntent(query.trim())
            ?: return ActionResult(false, str(R.string.exec_app_not_found, query))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, str(R.string.act_open_app, query))
        }.getOrElse { ActionResult(false, str(R.string.exec_open_app_failed, it.message ?: "")) }
    }

    private fun resolveLaunchIntent(query: String): Intent? {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage(query)?.let { return it }
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val exact = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(query, ignoreCase = true)
        }
        val contains = exact ?: apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true)
        } ?: apps.firstOrNull { it.packageName.contains(query, ignoreCase = true) }
        return contains?.let { pm.getLaunchIntentForPackage(it.packageName) }
    }

    private companion object {
        /** How long request_screen waits for the user's consent decision before giving up. */
        const val GRANT_WAIT_MS = 30_000L
        const val GRANT_POLL_MS = 400L

        /** Aliases (lowercase) → package for the messengers users name most often. */
        val KNOWN_SHARE_TARGETS: List<Pair<List<String>, String>> = listOf(
            listOf("微信", "wechat", "weixin") to "com.tencent.mm",
            listOf("qq") to "com.tencent.mobileqq",
            listOf("钉钉", "dingtalk") to "com.alibaba.android.rimet",
            listOf("企业微信", "wecom") to "com.tencent.wework",
            listOf("飞书", "lark", "feishu") to "com.ss.android.lark",
            listOf("微博", "weibo") to "com.sina.weibo",
            listOf("telegram", "电报") to "org.telegram.messenger",
            listOf("whatsapp") to "com.whatsapp",
            listOf("line") to "jp.naver.line.android",
            listOf("短信", "信息", "sms", "message") to "com.android.mms",
        )

        /** Duration units for the alarm/timer spec（"1小时20分钟"、"90 秒"、"5min"）. */
        val DURATION_UNITS = listOf(
            Regex("(\\d+)\\s*(?:小时|時間|时|hours?|hrs?|h)") to 3600,
            Regex("(\\d+)\\s*(?:分钟|分|minutes?|mins?|m)") to 60,
            Regex("(\\d+)\\s*(?:秒钟?|seconds?|secs?|s)") to 1,
        )

        /** Keywords (lowercase) → system settings page actions for open_settings. */
        val SETTINGS_PAGES: List<Pair<List<String>, String>> = listOf(
            listOf("wifi", "wlan", "无线") to android.provider.Settings.ACTION_WIFI_SETTINGS,
            listOf("蓝牙", "bluetooth") to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
            listOf("飞行", "airplane") to android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            listOf("nfc") to android.provider.Settings.ACTION_NFC_SETTINGS,
            listOf("电池", "省电", "battery") to android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS,
            listOf("显示", "亮度", "display", "brightness") to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
            listOf("声音", "音量", "sound", "volume") to android.provider.Settings.ACTION_SOUND_SETTINGS,
            listOf("应用", "app") to android.provider.Settings.ACTION_APPLICATION_SETTINGS,
            listOf("位置", "定位", "location", "gps") to android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            listOf("日期", "时间", "date", "time") to android.provider.Settings.ACTION_DATE_SETTINGS,
            listOf("语言", "language") to android.provider.Settings.ACTION_LOCALE_SETTINGS,
            listOf("无障碍", "accessibility") to android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
            listOf("流量", "数据", "网络", "network", "data") to android.provider.Settings.ACTION_WIRELESS_SETTINGS,
        )

        /** Aliases (lowercase) → package for common music players (play_music targeting). */
        val KNOWN_MUSIC_APPS: List<Pair<List<String>, String>> = listOf(
            listOf("网易云", "netease", "cloudmusic") to "com.netease.cloudmusic",
            listOf("qq音乐", "qqmusic") to "com.tencent.qqmusic",
            listOf("酷狗", "kugou") to "com.kugou.android",
            listOf("酷我", "kuwo") to "cn.kuwo.player",
            listOf("汽水", "luna") to "com.luna.music",
            listOf("spotify") to "com.spotify.music",
            listOf("youtube music", "yt music") to "com.google.android.apps.youtube.music",
            listOf("apple music") to "com.apple.android.music",
        )
    }

    /**
     * 模型自主判断需要读屏时的按需授权：弹出系统 MediaProjection 同意弹窗（经隐形的
     * [com.cone.agent.vision.CaptureGrantActivity]），等待用户裁决后把结果以 observation 回传——
     * 同意后下一次观察就带真实截图；拒绝也算"执行成功"（结果是拒绝），不计入失败重试。
     */
    private suspend fun requestScreen(): ActionResult {
        if (captureManager.isRunning) {
            return ActionResult(true, str(R.string.exec_screen_granted), observation = "屏幕捕获已可用，继续任务。")
        }
        val fired = runCatching {
            context.startActivity(
                Intent(context, com.cone.agent.vision.CaptureGrantActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!fired) return ActionResult(false, str(R.string.exec_screen_grant_failed))

        val deadline = android.os.SystemClock.uptimeMillis() + GRANT_WAIT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (captureManager.isRunning) {
                return ActionResult(
                    true,
                    str(R.string.exec_screen_granted),
                    observation = "用户已授权屏幕捕获，下一步你将看到真实截图，继续任务。",
                )
            }
            if (com.cone.agent.vision.CaptureGrantActivity.denied) break
            delay(GRANT_POLL_MS)
        }
        return ActionResult(
            true,
            str(R.string.exec_screen_denied),
            observation = "用户没有授权屏幕捕获。不要再次请求；用现有能力（控件列表/直达接口）继续，实在无法完成就 finish 说明原因。",
        )
    }

    private suspend fun mcpCall(text: String?, app: String?): ActionResult {
        val result = mcpManager.get().callToolRaw(app, text)
        return result.fold(
            onSuccess = { out -> ActionResult(true, "MCP $text", observation = out.take(6000)) },
            onFailure = { e -> ActionResult(false, "MCP 调用失败: ${e.message}", observation = "MCP 调用失败: ${e.message}") },
        )
    }

    private suspend fun scroll(direction: String?): ActionResult {
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val cx = w / 2
        val cy = h / 2
        val lowY = (h * 0.30f).toInt()
        val highY = (h * 0.70f).toInt()
        val lowX = (w * 0.30f).toInt()
        val highX = (w * 0.70f).toInt()
        val ok = when (direction?.lowercase()) {
            "up" -> bridge.swipe(cx, lowY, cx, highY, 300L)
            "down", null -> bridge.swipe(cx, highY, cx, lowY, 300L)
            "left" -> bridge.swipe(highX, cy, lowX, cy, 300L)
            "right" -> bridge.swipe(lowX, cy, highX, cy, 300L)
            else -> bridge.swipe(cx, highY, cx, lowY, 300L)
        }
        return ActionResult(ok, str(R.string.act_scroll, direction ?: "down"))
    }
}
