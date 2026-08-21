package com.cone.agent.watch

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the phone will tell the watch to use for speech, as typed on the phone.
 *
 * Mirrors the watch's own `TtsConfig`/`AsrConfig` but keyed by provider **name**: which row a name
 * maps to differs per device, and the watch resolves it on arrival. No credential lives here — the
 * key still comes from the provider row the name points at.
 */
data class WatchSpeechPrefs(
    val ttsUseProvider: Boolean = false,
    val ttsProviderName: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    val asrUseProvider: Boolean = false,
    val asrProviderName: String = "",
    val asrModel: String = "",
    val asrLanguage: String = "",
    /** "chat" (default, works on nearly every relay) or "transcriptions". */
    val asrTransport: String = "chat",
) {
    fun toPush(): PushSpeech = PushSpeech(
        ttsEngine = if (ttsUseProvider) "provider" else "device",
        ttsProviderName = ttsProviderName,
        ttsModel = ttsModel.trim(),
        ttsVoice = ttsVoice.trim(),
        asrEngine = if (asrUseProvider) "provider" else "device",
        asrProviderName = asrProviderName,
        asrModel = asrModel.trim(),
        asrLanguage = asrLanguage.trim(),
        asrTransport = asrTransport,
    )
}

/**
 * Persists [WatchSpeechPrefs] on the phone.
 *
 * Its own SharedPreferences rather than the app's DataStore: these are the *watch's* settings being
 * staged here, not the phone's, and mixing them into the phone's settings store would make them look
 * like something that changes this app's behaviour. Plain and synchronous is enough for nine short
 * strings that only the setup screen reads.
 */
@Singleton
class WatchSpeechStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("cone_watch_speech", Context.MODE_PRIVATE)

    fun load(): WatchSpeechPrefs = WatchSpeechPrefs(
        ttsUseProvider = prefs.getBoolean(KEY_TTS_USE_PROVIDER, false),
        ttsProviderName = prefs.getString(KEY_TTS_PROVIDER, "").orEmpty(),
        ttsModel = prefs.getString(KEY_TTS_MODEL, "").orEmpty(),
        ttsVoice = prefs.getString(KEY_TTS_VOICE, "").orEmpty(),
        asrUseProvider = prefs.getBoolean(KEY_ASR_USE_PROVIDER, false),
        asrProviderName = prefs.getString(KEY_ASR_PROVIDER, "").orEmpty(),
        asrModel = prefs.getString(KEY_ASR_MODEL, "").orEmpty(),
        asrLanguage = prefs.getString(KEY_ASR_LANGUAGE, "").orEmpty(),
        asrTransport = prefs.getString(KEY_ASR_TRANSPORT, "chat").orEmpty().ifBlank { "chat" },
    )

    fun save(value: WatchSpeechPrefs) {
        prefs.edit()
            .putBoolean(KEY_TTS_USE_PROVIDER, value.ttsUseProvider)
            .putString(KEY_TTS_PROVIDER, value.ttsProviderName)
            .putString(KEY_TTS_MODEL, value.ttsModel)
            .putString(KEY_TTS_VOICE, value.ttsVoice)
            .putBoolean(KEY_ASR_USE_PROVIDER, value.asrUseProvider)
            .putString(KEY_ASR_PROVIDER, value.asrProviderName)
            .putString(KEY_ASR_MODEL, value.asrModel)
            .putString(KEY_ASR_LANGUAGE, value.asrLanguage)
            .putString(KEY_ASR_TRANSPORT, value.asrTransport)
            .apply()
    }

    private companion object {
        const val KEY_TTS_USE_PROVIDER = "tts_use_provider"
        const val KEY_TTS_PROVIDER = "tts_provider"
        const val KEY_TTS_MODEL = "tts_model"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_ASR_USE_PROVIDER = "asr_use_provider"
        const val KEY_ASR_PROVIDER = "asr_provider"
        const val KEY_ASR_MODEL = "asr_model"
        const val KEY_ASR_LANGUAGE = "asr_language"
        const val KEY_ASR_TRANSPORT = "asr_transport"
    }
}
