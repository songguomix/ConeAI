package com.cone.agent.assistant

import android.content.Context
import android.media.MediaPlayer
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.data.repository.TtsConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads a Q&A answer aloud on demand, through the user's own TTS endpoint when one is configured and
 * the phone's built-in engine otherwise.
 *
 * Deliberately a process-wide singleton rather than per-message state: only one thing can be spoken
 * at a time, so a shared owner is what makes "tap another answer and the first stops" fall out for
 * free — and it keeps a single [TtsSpeaker] alive instead of constructing an engine per bubble in a
 * scrolling list. [speakingId] lets whichever bubble is currently talking show a stop button.
 */
object AnswerSpeech {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var systemSpeaker: TtsSpeaker? = null
    private var player: MediaPlayer? = null

    private val _speakingId = MutableStateFlow<Long?>(null)

    /** Id of the message being spoken, or null when silent. */
    val speakingId: StateFlow<Long?> = _speakingId.asStateFlow()

    /** Starts reading [text], or stops if [messageId] is already the one playing. */
    fun toggle(context: Context, messageId: Long, text: String) {
        if (_speakingId.value == messageId) {
            stop()
            return
        }
        stop()
        if (text.isBlank()) return
        _speakingId.value = messageId
        val app = context.applicationContext

        scope.launch {
            val config = runCatching { ttsConfig(app) }.getOrNull()
            if (config != null && speakRemote(app, config, text)) return@launch
            // No endpoint, or it failed — the built-in engine still gets the answer read out.
            speakSystem(app, text)
        }
    }

    fun stop() {
        _speakingId.value = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { systemSpeaker?.stop() }
    }

    private fun speakSystem(context: Context, text: String) {
        val speaker = systemSpeaker ?: TtsSpeaker(context).also { systemSpeaker = it }
        speaker.speak(text)
        // The platform engine reports completion through a listener we don't wire up here; clearing
        // the indicator on a rough estimate keeps the button honest without pretending to know more
        // than we do — the user can always tap it again to stop.
        scope.launch {
            val id = _speakingId.value
            kotlinx.coroutines.delay(estimateDurationMs(text))
            if (_speakingId.value == id) _speakingId.value = null
        }
    }

    /** Synthesises through the user's endpoint and plays the returned audio. False = fall back. */
    private suspend fun speakRemote(context: Context, config: TtsConfig, text: String): Boolean {
        val file = withContext(Dispatchers.IO) {
            runCatching { synthesize(context, config, text) }.getOrNull()
        } ?: return false
        return runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _speakingId.value = null
                    runCatching { it.release() }
                    if (player === it) player = null
                    runCatching { file.delete() }
                }
                setOnErrorListener { _, _, _ -> _speakingId.value = null; true }
                prepare()
                start()
            }
            player = mp
            true
        }.getOrDefault(false).also { if (!it) runCatching { file.delete() } }
    }

    private fun synthesize(context: Context, config: TtsConfig, text: String): File? {
        val body = JSONObject()
            .put("model", config.model)
            .put("voice", config.voice)
            .put("input", text.take(MAX_CHARS))
            .put("response_format", "mp3")
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(speechUrl(config.baseUrl))
            .apply {
                config.apiKey.trim().takeIf { it.isNotEmpty() }
                    ?.let { header("Authorization", "Bearer $it") }
            }
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null
            val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            file.writeBytes(bytes)
            return file
        }
    }

    private suspend fun ttsConfig(context: Context): TtsConfig? =
        EntryPointAccessors.fromApplication(context, SpeechSettingsEntryPoint::class.java)
            .settingsRepository().ttsConfig.first()

    /** Roughly how long [text] takes to read, used only to clear the playing indicator. */
    private fun estimateDurationMs(text: String): Long =
        (text.length * MS_PER_CHAR).toLong().coerceIn(1_500L, 10 * 60_000L)

    /** Completes a base URL the same way the chat and ASR endpoints do. Supports chat/completions as source. */
    fun speechUrl(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.contains("/chat/completions")) base = base.substringBefore("/chat/completions").trimEnd('/')
        if (base.contains("/audio/speech")) return base.substringBefore("/audio/speech") + "/audio/speech"
        val endpoint = "/audio/speech"
        return when {
            base.endsWith(endpoint) -> base
            base.endsWith("/speech") -> base
            Regex("^v\\d+.*", RegexOption.IGNORE_CASE).matches(base.substringAfterLast('/')) ->
                "$base$endpoint"
            else -> "$base/v1$endpoint"
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SpeechSettingsEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val MAX_CHARS = 4_000
    private const val MS_PER_CHAR = 180.0
}
