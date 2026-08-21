package com.cone.agent.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cone.agent.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = Constants.SETTINGS_STORE)

/** The model currently selected as the agent brain. */
data class SelectedAgentModel(
    val providerId: Long,
    val modelId: String,
)

/** A user-supplied text-to-speech endpoint (OpenAI `/v1/audio/speech` compatible). */
data class TtsConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val voice: String,
)

/** A user-supplied speech-to-text endpoint (OpenAI `/v1/audio/transcriptions` compatible). */
data class AsrConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** ISO-639-1 hint (`zh`, `en`, …); blank lets the service auto-detect. */
    val language: String,
)

/**
 * How the agent turns the screen into something the thinking model can read. The three modes differ
 * in exactly one respect — what the model is given — and that decides which models can be used:
 *
 *  - [LOCAL_OCR]    on-device OCR supplies the screen's text and coordinates, and **no screenshot is
 *                   uploaded at all**. Any model works, including text-only ones. Attaching an image
 *                   here would defeat the point: a text-only model errors out the moment it receives
 *                   one, which is precisely the conflict this mode exists to avoid.
 *  - [CLOUD_SINGLE] the screenshot goes straight to the agent model, which must support vision.
 *                   The most capable option — it sees icons, layout and colour, not just text.
 *  - [CLOUD_DUAL]   a separate vision model describes the screenshot and the agent model reasons
 *                   from that description, so the agent model may again be text-only. Costs one
 *                   extra cloud call per step.
 */
enum class ScreenMode(val wire: String) {
    LOCAL_OCR("local_ocr"),
    CLOUD_SINGLE("cloud_single"),
    CLOUD_DUAL("cloud_dual");

    val isCloud: Boolean get() = this != LOCAL_OCR

    companion object {
        fun from(value: String?): ScreenMode =
            entries.firstOrNull { it.wire == value } ?: LOCAL_OCR
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.dataStore

    val selectedModel: Flow<SelectedAgentModel?> = store.data.map { prefs ->
        val pid = prefs[KEY_PROVIDER_ID] ?: return@map null
        val mid = prefs[KEY_MODEL_ID] ?: return@map null
        if (mid.isBlank()) null else SelectedAgentModel(pid, mid)
    }

    /**
     * 智能体的「屏幕识别方式」，见 [ScreenMode]。
     *
     * 未写过新键时从旧的「拼凑双模型」开关迁移：原本开着双模型的用户升级后仍是 [ScreenMode.CLOUD_DUAL]，
     * 那是他们主动选过的；没开的落在新默认 [ScreenMode.LOCAL_OCR]（旧的"关"只是默认值，并非明确选择）。
     */
    val screenMode: Flow<ScreenMode> = store.data.map { prefs ->
        prefs[KEY_SCREEN_MODE]?.let { return@map ScreenMode.from(it) }
        if (prefs[KEY_DUAL_MODEL] == true) ScreenMode.CLOUD_DUAL else ScreenMode.LOCAL_OCR
    }

    /** 云端识别（拼凑双模型）下负责「看截图 → 文字描述画面」的视觉模型（必须支持视觉）。 */
    val visionModel: Flow<SelectedAgentModel?> = store.data.map { prefs ->
        val pid = prefs[KEY_VISION_PROVIDER_ID] ?: return@map null
        val mid = prefs[KEY_VISION_MODEL_ID] ?: return@map null
        if (mid.isBlank()) null else SelectedAgentModel(pid, mid)
    }

    /**
     * 问答「拼凑」（双模型）开关，独立于智能体：开启后由 [chatVisionModel] 先描述用户在问答里发送的图片，
     * 再由 [chatModel] 据描述作答（此时 [chatModel] 可为纯文本模型，也能"看图"）。默认关闭。
     */
    val chatDualModelEnabled: Flow<Boolean> = store.data.map { it[KEY_CHAT_DUAL_MODEL] ?: false }

    /** 问答双模型模式下负责「看图 → 文字描述」的视觉模型（必须支持视觉），独立于智能体的 [visionModel]。 */
    val chatVisionModel: Flow<SelectedAgentModel?> = store.data.map { prefs ->
        val pid = prefs[KEY_CHAT_VISION_PROVIDER_ID] ?: return@map null
        val mid = prefs[KEY_CHAT_VISION_MODEL_ID] ?: return@map null
        if (mid.isBlank()) null else SelectedAgentModel(pid, mid)
    }

    /** The model used for plain Q&A (问答) mode; may differ from the agent's vision model. */
    val chatModel: Flow<SelectedAgentModel?> = store.data.map { prefs ->
        val pid = prefs[KEY_CHAT_PROVIDER_ID] ?: return@map null
        val mid = prefs[KEY_CHAT_MODEL_ID] ?: return@map null
        if (mid.isBlank()) null else SelectedAgentModel(pid, mid)
    }

    val maxSteps: Flow<Int> = store.data.map { it[KEY_MAX_STEPS] ?: Constants.DEFAULT_MAX_STEPS }

    /** When true, the user has chosen to also auto-confirm high-risk steps (off by default). */
    val autoConfirmHighRisk: Flow<Boolean> = store.data.map { it[KEY_AUTO_CONFIRM] ?: false }

    /**
     * 计划模式：开启后任务先只生成总体计划并等用户确认，确认前不执行任何动作（默认关闭）。
     */
    val planModeEnabled: Flow<Boolean> = store.data.map { it[KEY_PLAN_MODE] ?: false }

    /**
     * 写代码's own plan-mode switch, deliberately separate from the 智能体 one above.
     *
     * They gate different risks — one is about touching the screen, the other about rewriting files —
     * and the toggle now lives on the 代码 page itself, so sharing a flag would mean flipping it there
     * silently changed how the phone agent behaves.
     */
    val codePlanMode: Flow<Boolean> = store.data.map { it[KEY_CODE_PLAN_MODE] ?: false }

    /** When true, the voice assistant reads its 思考 answers aloud (on by default; user can mute). */
    val voiceReplyEnabled: Flow<Boolean> = store.data.map { it[KEY_VOICE_REPLY] ?: true }

    /** When true, 问答 augments the question with live web-search results (off by default). */
    val webSearchEnabled: Flow<Boolean> = store.data.map { it[KEY_WEB_SEARCH] ?: false }

    /**
     * Built-in browser search engine: "auto" (pick by IP — mainland China → 百度, else Google) or a
     * concrete engine id ("baidu"/"google"/"bing"). Defaults to "auto".
     */
    val searchEngine: Flow<String> = store.data.map { it[KEY_SEARCH_ENGINE] ?: "auto" }

    /** When true, the always-on "Hey pine cone" / "松果" wake-word service runs (off by default). */
    val wakeWordEnabled: Flow<Boolean> = store.data.map { it[KEY_WAKE_WORD] ?: false }

    /** Wake-word match leniency in [0f, 1f]: lower = stricter (fewer false triggers), higher = easier. */
    val wakeWordSensitivity: Flow<Float> =
        store.data.map { it[KEY_WAKE_SENSITIVITY] ?: Constants.DEFAULT_WAKE_SENSITIVITY }

    /** UI language tag: "zh" (default), "en" or "ja". Applied app-wide by [com.cone.agent.MainActivity]. */
    val appLanguage: Flow<String> = store.data.map { it[KEY_APP_LANGUAGE] ?: "zh" }

    /**
     * The last ConeCode desktop-remote pairing URL (full `http://host:port/?t=token`), remembered so
     * the remote screen can offer a one-tap reconnect. Null until the user pairs once.
     */
    val remoteEndpoint: Flow<String?> = store.data.map { it[KEY_REMOTE_ENDPOINT]?.takeIf { v -> v.isNotBlank() } }

    suspend fun selectModel(providerId: Long, modelId: String) {
        store.edit {
            it[KEY_PROVIDER_ID] = providerId
            it[KEY_MODEL_ID] = modelId
        }
    }

    suspend fun selectChatModel(providerId: Long, modelId: String) {
        store.edit {
            it[KEY_CHAT_PROVIDER_ID] = providerId
            it[KEY_CHAT_MODEL_ID] = modelId
        }
    }

    /**
     * 自定义语音识别（ASR）服务：OpenAI `/v1/audio/transcriptions` 兼容接口。填了地址就用它替代系统
     * 语音识别，用于语音助手与输入框麦克风。**不影响语音唤醒**——唤醒必须常驻离线、零延迟、不联网，
     * 那是 Vosk 的职责，与这里无关。
     */
    val asrConfig: Flow<AsrConfig?> = store.data.map { prefs ->
        val url = prefs[KEY_ASR_BASE_URL]?.trim().orEmpty()
        if (url.isEmpty()) return@map null
        AsrConfig(
            baseUrl = url,
            apiKey = prefs[KEY_ASR_API_KEY].orEmpty(),
            model = prefs[KEY_ASR_MODEL]?.trim().orEmpty().ifEmpty { "whisper-1" },
            language = prefs[KEY_ASR_LANGUAGE]?.trim().orEmpty(),
        )
    }

    suspend fun setAsrConfig(baseUrl: String, apiKey: String, model: String, language: String) {
        store.edit {
            it[KEY_ASR_BASE_URL] = baseUrl.trim()
            it[KEY_ASR_API_KEY] = apiKey.trim()
            it[KEY_ASR_MODEL] = model.trim()
            it[KEY_ASR_LANGUAGE] = language.trim()
        }
    }

    /**
     * 自定义语音合成（TTS）服务：OpenAI `/v1/audio/speech` 兼容接口。填了地址就用它替代系统 TTS，
     * 用于问答朗读与语音助手播报。留空则沿用系统自带引擎。
     */
    val ttsConfig: Flow<TtsConfig?> = store.data.map { prefs ->
        val url = prefs[KEY_TTS_BASE_URL]?.trim().orEmpty()
        if (url.isEmpty()) return@map null
        TtsConfig(
            baseUrl = url,
            apiKey = prefs[KEY_TTS_API_KEY].orEmpty(),
            model = prefs[KEY_TTS_MODEL]?.trim().orEmpty().ifEmpty { "tts-1" },
            voice = prefs[KEY_TTS_VOICE]?.trim().orEmpty().ifEmpty { "alloy" },
        )
    }

    suspend fun setTtsConfig(baseUrl: String, apiKey: String, model: String, voice: String) {
        store.edit {
            it[KEY_TTS_BASE_URL] = baseUrl.trim()
            it[KEY_TTS_API_KEY] = apiKey.trim()
            it[KEY_TTS_MODEL] = model.trim()
            it[KEY_TTS_VOICE] = voice.trim()
        }
    }

    suspend fun setScreenMode(value: ScreenMode) {
        store.edit {
            it[KEY_SCREEN_MODE] = value.wire
            // Keep the legacy flag in step so a downgrade to an older build still behaves sanely.
            it[KEY_DUAL_MODEL] = value == ScreenMode.CLOUD_DUAL
        }
    }

    suspend fun selectVisionModel(providerId: Long, modelId: String) {
        store.edit {
            it[KEY_VISION_PROVIDER_ID] = providerId
            it[KEY_VISION_MODEL_ID] = modelId
        }
    }

    suspend fun setChatDualModelEnabled(value: Boolean) {
        store.edit { it[KEY_CHAT_DUAL_MODEL] = value }
    }

    suspend fun selectChatVisionModel(providerId: Long, modelId: String) {
        store.edit {
            it[KEY_CHAT_VISION_PROVIDER_ID] = providerId
            it[KEY_CHAT_VISION_MODEL_ID] = modelId
        }
    }

    suspend fun setMaxSteps(value: Int) {
        // The unlimited sentinel is stored verbatim; any other value is clamped to a sane bounded range.
        val sanitized = if (value >= Constants.UNLIMITED_MAX_STEPS) Constants.UNLIMITED_MAX_STEPS else value.coerceIn(1, 100)
        store.edit { it[KEY_MAX_STEPS] = sanitized }
    }

    suspend fun setAutoConfirmHighRisk(value: Boolean) {
        store.edit { it[KEY_AUTO_CONFIRM] = value }
    }

    suspend fun setCodePlanMode(value: Boolean) {
        store.edit { it[KEY_CODE_PLAN_MODE] = value }
    }

    suspend fun setPlanModeEnabled(value: Boolean) {
        store.edit { it[KEY_PLAN_MODE] = value }
    }

    suspend fun setVoiceReplyEnabled(value: Boolean) {
        store.edit { it[KEY_VOICE_REPLY] = value }
    }

    suspend fun setWebSearchEnabled(value: Boolean) {
        store.edit { it[KEY_WEB_SEARCH] = value }
    }

    suspend fun setSearchEngine(value: String) {
        store.edit { it[KEY_SEARCH_ENGINE] = value }
    }

    suspend fun setWakeWordEnabled(value: Boolean) {
        store.edit { it[KEY_WAKE_WORD] = value }
    }

    suspend fun setWakeWordSensitivity(value: Float) {
        val sanitized = value.coerceIn(0f, 1f)
        store.edit { it[KEY_WAKE_SENSITIVITY] = sanitized }
        // Mirror to a synchronous store so WakeWordService can read it on a cold (sticky) restart.
        com.cone.agent.core.WakeTuning.setSensitivity(context, sanitized)
    }

    suspend fun setAppLanguage(tag: String) {
        store.edit { it[KEY_APP_LANGUAGE] = tag }
        // Mirror to a synchronous store so the assistant Activity / agent runtime can read it too.
        com.cone.agent.core.LocaleHelper.setLanguage(context, tag)
    }

    suspend fun setRemoteEndpoint(url: String?) {
        store.edit {
            if (url.isNullOrBlank()) it.remove(KEY_REMOTE_ENDPOINT) else it[KEY_REMOTE_ENDPOINT] = url
        }
    }

    /** True when a biometric-protected remote login (encrypted url+password) is saved. */
    val hasRemoteSecret: Flow<Boolean> = store.data.map { !it[KEY_REMOTE_SECRET].isNullOrBlank() }

    /** Read the stored (cipherText, iv) of the encrypted remote login, or null if none. */
    suspend fun readRemoteSecret(): Pair<String, String>? {
        val prefs = store.data.first()
        val cipher = prefs[KEY_REMOTE_SECRET]?.takeIf { it.isNotBlank() } ?: return null
        val iv = prefs[KEY_REMOTE_SECRET_IV]?.takeIf { it.isNotBlank() } ?: return null
        return cipher to iv
    }

    suspend fun setRemoteSecret(cipherText: String, iv: String) {
        store.edit { it[KEY_REMOTE_SECRET] = cipherText; it[KEY_REMOTE_SECRET_IV] = iv }
    }

    suspend fun clearRemoteSecret() {
        store.edit { it.remove(KEY_REMOTE_SECRET); it.remove(KEY_REMOTE_SECRET_IV) }
    }

    private companion object {
        val KEY_PROVIDER_ID = longPreferencesKey("selected_provider_id")
        val KEY_MODEL_ID = stringPreferencesKey("selected_model_id")
        val KEY_CHAT_PROVIDER_ID = longPreferencesKey("chat_provider_id")
        val KEY_CHAT_MODEL_ID = stringPreferencesKey("chat_model_id")
        val KEY_DUAL_MODEL = booleanPreferencesKey("dual_model_enabled")
        val KEY_SCREEN_MODE = stringPreferencesKey("screen_mode")
        val KEY_ASR_BASE_URL = stringPreferencesKey("asr_base_url")
        val KEY_ASR_API_KEY = stringPreferencesKey("asr_api_key")
        val KEY_ASR_MODEL = stringPreferencesKey("asr_model")
        val KEY_ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val KEY_TTS_BASE_URL = stringPreferencesKey("tts_base_url")
        val KEY_TTS_API_KEY = stringPreferencesKey("tts_api_key")
        val KEY_TTS_MODEL = stringPreferencesKey("tts_model")
        val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        val KEY_VISION_PROVIDER_ID = longPreferencesKey("vision_provider_id")
        val KEY_VISION_MODEL_ID = stringPreferencesKey("vision_model_id")
        val KEY_CHAT_DUAL_MODEL = booleanPreferencesKey("chat_dual_model_enabled")
        val KEY_CHAT_VISION_PROVIDER_ID = longPreferencesKey("chat_vision_provider_id")
        val KEY_CHAT_VISION_MODEL_ID = stringPreferencesKey("chat_vision_model_id")
        val KEY_MAX_STEPS = intPreferencesKey("max_steps")
        val KEY_AUTO_CONFIRM = booleanPreferencesKey("auto_confirm_high_risk")
        val KEY_PLAN_MODE = booleanPreferencesKey("plan_mode_enabled")
        val KEY_CODE_PLAN_MODE = booleanPreferencesKey("code_plan_mode_enabled")
        val KEY_VOICE_REPLY = booleanPreferencesKey("voice_reply_enabled")
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_WEB_SEARCH = booleanPreferencesKey("web_search_enabled")
        val KEY_SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val KEY_WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
        val KEY_WAKE_SENSITIVITY = floatPreferencesKey("wake_sensitivity")
        val KEY_REMOTE_ENDPOINT = stringPreferencesKey("remote_endpoint")
        val KEY_REMOTE_SECRET = stringPreferencesKey("remote_secret_cipher")
        val KEY_REMOTE_SECRET_IV = stringPreferencesKey("remote_secret_iv")
    }
}
