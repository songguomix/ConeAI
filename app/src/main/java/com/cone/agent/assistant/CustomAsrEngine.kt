package com.cone.agent.assistant

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.cone.agent.data.repository.AsrConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Records a phrase and transcribes it through the user's own OpenAI-compatible
 * `/v1/audio/transcriptions` endpoint.
 *
 * Unlike the platform recognizer this is a *batch* transcriber: audio is captured to a file, then
 * uploaded once the phrase ends, so there are no partial results — the caller sees a single final
 * transcript. Because the service can't tell us when the speaker stopped, end-of-speech is detected
 * locally from the microphone amplitude ([SILENCE_HOLD_MS] of quiet after speech has started), which
 * is also what drives the waveform the UI already renders.
 *
 * Kept independent of [SpeechController]'s engine list: that class negotiates with system
 * recognizers, while this one only needs a microphone and an HTTP endpoint.
 */
class CustomAsrEngine(
    private val context: Context,
    private val config: AsrConfig,
) {
    interface Callbacks {
        fun onReady()
        fun onLevel(normalized: Float)
        fun onFinal(text: String)
        /** Recoverable: nothing heard, or the endpoint refused this attempt. */
        fun onError(message: String)
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    @Volatile private var finished = false
    @Volatile private var speechStarted = false

    fun start(callbacks: Callbacks) {
        finished = false
        speechStarted = false
        val file = File(context.cacheDir, "asr_${System.currentTimeMillis()}.m4a")
        outputFile = file

        val rec = runCatching {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Whisper-class models resample to 16 kHz anyway; capturing there keeps the upload
                // small on mobile data without giving up any accuracy.
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(32_000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        }.getOrElse {
            cleanup()
            callbacks.onError(it.message.orEmpty().ifBlank { "无法打开麦克风" })
            return
        }
        recorder = rec
        callbacks.onReady()

        job = scope.launch {
            var quietFor = 0L
            var elapsed = 0L
            while (isActive && !finished) {
                delay(POLL_MS)
                elapsed += POLL_MS
                val amplitude = runCatching { rec.maxAmplitude }.getOrDefault(0)
                val level = (amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
                callbacks.onLevel(level)

                if (amplitude > SPEECH_AMPLITUDE) {
                    speechStarted = true
                    quietFor = 0L
                } else if (speechStarted) {
                    quietFor += POLL_MS
                }
                // Stop on a natural pause after real speech, on a long silence with no speech at
                // all, or at the hard ceiling so a stuck stream can't record forever.
                val done = (speechStarted && quietFor >= SILENCE_HOLD_MS) ||
                    (!speechStarted && elapsed >= NO_SPEECH_TIMEOUT_MS) ||
                    elapsed >= MAX_RECORD_MS
                if (done) {
                    finishAndTranscribe(callbacks)
                    return@launch
                }
            }
        }
    }

    /** The user tapped to stop — transcribe whatever was captured. */
    fun stop(callbacks: Callbacks) {
        if (finished) return
        job?.cancel()
        scope.launch { finishAndTranscribe(callbacks) }
    }

    fun destroy() {
        finished = true
        job?.cancel()
        job = null
        cleanup()
    }

    private suspend fun finishAndTranscribe(callbacks: Callbacks) {
        if (finished) return
        finished = true
        val file = outputFile
        val heardSpeech = speechStarted
        runCatching {
            recorder?.stop()
        }.onFailure {
            // stop() throws when the clip is too short to have produced a valid file.
            cleanup()
            callbacks.onError("没有听到语音")
            return
        }
        cleanup()

        if (file == null || !file.exists() || file.length() < MIN_AUDIO_BYTES || !heardSpeech) {
            runCatching { file?.delete() }
            callbacks.onError("没有听到语音")
            return
        }
        val result = withContext(Dispatchers.IO) { transcribe(file) }
        runCatching { file.delete() }
        result.fold(
            onSuccess = { text ->
                if (text.isBlank()) callbacks.onError("没有听到语音") else callbacks.onFinal(text)
            },
            onFailure = { callbacks.onError(it.message.orEmpty().ifBlank { "语音识别失败" }) },
        )
    }

    private fun transcribe(file: File): Result<String> = runCatching {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(AUDIO_MEDIA_TYPE))
            .addFormDataPart("model", config.model)
            .apply {
                if (config.language.isNotBlank()) addFormDataPart("language", config.language)
            }
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(transcriptionUrl(config.baseUrl))
            .apply {
                config.apiKey.trim().takeIf { it.isNotEmpty() }
                    ?.let { header("Authorization", "Bearer $it") }
            }
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(describeFailure(response.code, raw))
            }
            // Standard shape is {"text": "..."}; some gateways answer with plain text instead.
            runCatching { JSONObject(raw).optString("text") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: raw.trim()
        }
    }

    /** Surfaces why the endpoint refused, instead of a bare status code. */
    private fun describeFailure(code: Int, raw: String): String {
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().orEmpty().ifBlank { raw.take(160) }
        val hint = when (code) {
            401, 403 -> "语音识别服务拒绝了密钥"
            404 -> "语音识别地址或模型不存在"
            413 -> "录音过长，服务不接受"
            429 -> "语音识别服务限流或额度不足"
            in 500..599 -> "语音识别服务异常"
            else -> "语音识别失败"
        }
        return if (detail.isBlank()) "$hint（HTTP $code）" else "$hint（HTTP $code）：$detail"
    }

    private fun cleanup() {
        runCatching { recorder?.release() }
        recorder = null
    }

    companion object {
        /**
         * Completes a user-entered base URL the same way the chat endpoints do: a bare host gains
         * `/v1/audio/transcriptions`, a `/v1`-style root gains only the endpoint, and a URL that
         * already names the endpoint is used verbatim.
         */
        fun transcriptionUrl(baseUrl: String): String {
            var base = baseUrl.trim().trimEnd('/')
            if (base.contains("/chat/completions")) base = base.substringBefore("/chat/completions").trimEnd('/')
            if (base.contains("/audio/transcriptions")) return base.substringBefore("/audio") + "/audio/transcriptions"
            val endpoint = "/audio/transcriptions"
            return when {
                base.endsWith(endpoint) -> base
                base.endsWith("/transcriptions") -> base
                Regex("^v\\d+.*", RegexOption.IGNORE_CASE).matches(base.substringAfterLast('/')) ->
                    "$base$endpoint"
                else -> "$base/v1$endpoint"
            }
        }

        private val AUDIO_MEDIA_TYPE = "audio/mp4".toMediaType()

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        private const val POLL_MS = 100L
        /** MediaRecorder reports 0..32767; used to normalise the level for the waveform. */
        private const val MAX_AMPLITUDE = 20_000f
        private const val SPEECH_AMPLITUDE = 1_800
        private const val SILENCE_HOLD_MS = 1_200L
        private const val NO_SPEECH_TIMEOUT_MS = 6_000L
        private const val MAX_RECORD_MS = 60_000L
        private const val MIN_AUDIO_BYTES = 2_048L
    }
}
