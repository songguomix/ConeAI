package com.cone.agent.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.repository.AsrConfig
import com.cone.agent.data.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Thin wrapper over the platform [SpeechRecognizer]. Must be created and driven on the main thread.
 * Reports partial transcripts (for live display), a final transcript, the microphone level (for the
 * waveform) and errors back through [Callbacks].
 *
 * Picking the right engine is the tricky part here: Cone Agent declares its own (no-op) recognition
 * service so it can be a voice-interaction assistant, and once the user selects Cone as the default
 * assistant the system often points `Settings.Secure.voice_recognition_service` at *our* stub. So we
 * never trust the default — we resolve a real, working recognizer ourselves and fall back across
 * several engines (online Google → other Google → any third-party → on-device) until one works. This
 * is what fixes the spurious "识别客户端错误" / "缺少麦克风权限" the default path produces while we're
 * the assistant.
 */
class SpeechController(private val context: Context) {

    interface Callbacks {
        fun onReadyForSpeech() {}
        fun onLevel(normalized: Float) {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        /** A recoverable problem for this attempt (e.g. didn't hear anything) — the user can retry. */
        fun onError(message: String) {}
        /** No speech engine could be made to work at all — fall back to typing. */
        fun onUnavailable(message: String) {}
    }

    /** An engine to attempt, in order, until one starts listening successfully. */
    private sealed interface Engine {
        data class Service(val component: ComponentName) : Engine
        object OnDevice : Engine
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var downloadRecognizer: SpeechRecognizer? = null
    private var callbacks: Callbacks? = null

    /** Non-null while a user-configured ASR endpoint is handling this session (see [start]). */
    private var asr: CustomAsrEngine? = null

    private var engines: List<Engine> = emptyList()
    private var engineIndex = 0
    private var retriedClient = false
    private var gotReady = false

    // If an engine binds but never calls back (e.g. a stub recognizer), move on instead of hanging.
    private val watchdog = Runnable {
        if (!gotReady) advanceOrFail(str(R.string.speech_start_timeout))
    }

    val isAvailable: Boolean get() = buildEngines().isNotEmpty()

    fun start(callbacks: Callbacks) {
        this.callbacks = callbacks

        // A user-configured ASR endpoint wins over every system recognizer: they went out of their
        // way to point at it, and on a phone with no usable recognizer (common without Google
        // services) it is the only thing that works at all. Routed here rather than at the call
        // sites so the voice assistant and the input-box mic both get it for free.
        // Wake-word detection deliberately does NOT come through here — it stays on the bundled
        // offline model, which must keep listening with no network and no per-phrase upload.
        asrConfig()?.let { config ->
            val engine = CustomAsrEngine(context, config)
            asr = engine
            engine.start(object : CustomAsrEngine.Callbacks {
                override fun onReady() = callbacks.onReadyForSpeech()
                override fun onLevel(normalized: Float) = callbacks.onLevel(normalized)
                override fun onFinal(text: String) = callbacks.onFinal(text)
                override fun onError(message: String) = callbacks.onError(message)
            })
            return
        }

        engines = buildEngines()
        engineIndex = 0
        if (engines.isEmpty()) {
            callbacks.onUnavailable(str(R.string.speech_no_engine))
            return
        }
        // Make sure the offline (on-device) model is being downloaded so future runs work without network.
        maybeTriggerOnDeviceDownload()
        startCurrent()
    }

    /**
     * Ask the platform to (pre)download the Google offline on-device recognition model for the
     * current language, so speech recognition can run without network. No-op once it's present, and
     * only available on Android 13+ (API 33).
     */
    private fun maybeTriggerOnDeviceDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            runCatching { downloadRecognizer?.destroy() }
            val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            downloadRecognizer = sr
            sr.triggerModelDownload(recognizerIntent(preferOffline = true))
        }
    }

    /** Ask the recognizer to finalize what it heard so far (e.g. user tapped the mic to stop). */
    fun stop() {
        handler.removeCallbacks(watchdog)
        asr?.let { engine ->
            val cb = callbacks ?: return@let
            engine.stop(object : CustomAsrEngine.Callbacks {
                override fun onReady() = Unit
                override fun onLevel(normalized: Float) = cb.onLevel(normalized)
                override fun onFinal(text: String) = cb.onFinal(text)
                override fun onError(message: String) = cb.onError(message)
            })
            return
        }
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        handler.removeCallbacks(watchdog)
        runCatching { asr?.destroy() }
        asr = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        runCatching { downloadRecognizer?.destroy() }
        recognizer = null
        downloadRecognizer = null
        callbacks = null
    }

    /**
     * Reads the ASR endpoint synchronously — [start] is a main-thread call with no scope of its own,
     * and this touches a small DataStore already in memory. Any failure simply means "not
     * configured", which falls through to the system recognizers.
     */
    private fun asrConfig(): AsrConfig? = runCatching {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SpeechEntryPoint::class.java,
        )
        runBlocking { entryPoint.settingsRepository().asrConfig.first() }
    }.getOrNull()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SpeechEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    private fun startCurrent() {
        val engine = engines.getOrNull(engineIndex) ?: run {
            callbacks?.onUnavailable(str(R.string.speech_start_failed))
            return
        }
        retriedClient = false
        gotReady = false
        runCatching { recognizer?.destroy() }
        val sr = createRecognizer(engine)
        if (sr == null) { advanceOrFail(str(R.string.speech_create_failed)); return }
        recognizer = sr
        sr.setRecognitionListener(listener)
        val intent = recognizerIntent(preferOffline = engine is Engine.OnDevice)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        runCatching { sr.startListening(intent) }
            .onFailure { advanceOrFail(str(R.string.speech_start_failed_reason, it.message ?: "")) }
    }

    private fun createRecognizer(engine: Engine): SpeechRecognizer? = runCatching {
        when (engine) {
            is Engine.Service -> SpeechRecognizer.createSpeechRecognizer(context, engine.component)
            Engine.OnDevice ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    null
                }
        }
    }.getOrNull()

    private fun recognizerIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val primary = Locale.getDefault().toLanguageTag()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
            // Multi-language: let the recognizer also consider these, so mixed / code-switched speech
            // (中文里夹英文 / 日本語 …) is transcribed instead of being forced into a single language.
            val additional = (MULTI_LANGUAGES - primary).toTypedArray()
            putExtra(EXTRA_ADDITIONAL_LANGUAGES, additional)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Ask for several hypotheses: the recognizer scores more candidates internally and we keep
            // the top one, which tends to be a slightly better transcription than max-results=1.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // End-pointing: the defaults cut off after ~1s of silence, chopping long sentences and
            // any natural mid-thought pause. Give the speaker room to breathe before we finalize, so
            // whole utterances are captured instead of half a sentence. (Read by Google's recognizer;
            // unknown extras are ignored by others.)
            putExtra(EXTRA_MIN_LENGTH_MS, 6_000)
            putExtra(EXTRA_COMPLETE_SILENCE_MS, 2_200)
            putExtra(EXTRA_POSSIBLY_COMPLETE_SILENCE_MS, 2_200)
        }

    /** Try the next engine; if there are none left, surface [message] to the user. */
    private fun advanceOrFail(message: String) {
        handler.removeCallbacks(watchdog)
        if (engineIndex < engines.lastIndex) {
            engineIndex++
            startCurrent()
        } else {
            callbacks?.onUnavailable(str(R.string.speech_unavailable_reason, message))
        }
    }

    /**
     * Builds the ordered list of engines to try. Crucially this *excludes our own stub service* (so
     * we never bind to the no-op recognizer the system may have made default), and prefers Google's
     * online quick-search recognizer over the on-device one (whose language pack is often missing).
     */
    private fun buildEngines(): List<Engine> {
        val pm = context.packageManager
        val services = runCatching {
            pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrDefault(emptyList())
        val candidates = services.mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName } // never our own no-op stub

        val ordered = LinkedHashSet<ComponentName>()

        // 1) The user's configured system recognizer — unless it's us (it often is, while we're the
        //    assistant), in which case skip it.
        runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        }.getOrNull()
            ?.let { ComponentName.unflattenFromString(it) }
            ?.takeIf { it.packageName != context.packageName }
            ?.takeIf { cfg -> candidates.any { it.packageName == cfg.packageName && it.name == cfg.className } }
            ?.let { ordered.add(it) }

        // 2) Google's online recognizer (most reliable: real network STT).
        candidates.firstOrNull { it.packageName == GOOGLE_QSB }
            ?.let { ordered.add(ComponentName(it.packageName, it.name)) }

        // 3) Any other Google recognizer (e.g. on-device Android System Intelligence).
        candidates.filter { it.packageName.contains("google", ignoreCase = true) }
            .forEach { ordered.add(ComponentName(it.packageName, it.name)) }

        // 4) Any remaining third-party recognizer.
        candidates.forEach { ordered.add(ComponentName(it.packageName, it.name)) }

        val list = ordered.map { Engine.Service(it) as Engine }.toMutableList()

        // 5) On-device recognizer (API 31+) as a last resort — works offline if a model is present.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list.add(Engine.OnDevice)
        return list
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            gotReady = true
            handler.removeCallbacks(watchdog)
            callbacks?.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // RMS is roughly -2..12 dB; map to 0..1 for the waveform.
            val normalized = ((rmsdB + 2f) / 14f).coerceIn(0f, 1f)
            callbacks?.onLevel(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            handler.removeCallbacks(watchdog)
            // ERROR_CLIENT is frequently a transient stale binding; retry the *same* engine once.
            if (error == SpeechRecognizer.ERROR_CLIENT && !retriedClient) {
                retriedClient = true
                startCurrent()
                return
            }
            // NO_MATCH / SPEECH_TIMEOUT mean the engine worked but heard nothing — that's a real
            // result for this engine, so report it. Anything else (client/permission/busy/server/
            // network/audio) is an engine problem: try the next one before giving up.
            if (isEngineProblem(error)) {
                if (engineIndex < engines.lastIndex) {
                    engineIndex++
                    startCurrent()
                } else {
                    // Every engine failed for a non-"heard nothing" reason — fall back to typing.
                    callbacks?.onUnavailable(str(R.string.speech_failed_reason, errorText(error)))
                }
                return
            }
            callbacks?.onError(errorText(error))
        }

        override fun onResults(results: Bundle?) {
            handler.removeCallbacks(watchdog)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            callbacks?.onFinal(tidyTranscript(text))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) callbacks?.onPartial(tidyTranscript(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun isEngineProblem(error: Int): Boolean =
        error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    /** Resolve a string in the user-selected app language (this runs outside Compose). */
    private fun str(@StringRes id: Int, vararg args: Any): String = LocaleHelper.string(context, id, *args)

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> str(R.string.speech_err_audio)
        SpeechRecognizer.ERROR_CLIENT -> str(R.string.speech_err_client)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> str(R.string.speech_err_permission)
        SpeechRecognizer.ERROR_NETWORK -> str(R.string.speech_err_network)
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> str(R.string.speech_err_network_timeout)
        SpeechRecognizer.ERROR_NO_MATCH -> str(R.string.speech_err_no_match)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> str(R.string.speech_err_busy)
        SpeechRecognizer.ERROR_SERVER -> str(R.string.speech_err_server)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> str(R.string.speech_err_speech_timeout)
        else -> str(R.string.speech_err_unknown, error)
    }

    private companion object {
        const val GOOGLE_QSB = "com.google.android.googlequicksearchbox"
        // Generous: a cold engine can take several seconds to first call onReadyForSpeech; only after
        // this with zero callbacks do we assume it's stuck and move to the next engine.
        const val WATCHDOG_MS = 10_000L

        // Extra the recognizer reads to also allow these languages alongside the primary one.
        const val EXTRA_ADDITIONAL_LANGUAGES = "android.speech.extra.ADDITIONAL_LANGUAGES"
        // Languages we let the recognizer consider together for mixed / code-switched speech.
        val MULTI_LANGUAGES = listOf("zh-CN", "en-US", "ja-JP")

        // Silence / length end-pointing extras (RecognizerIntent constants of the same name; used as
        // literals so the values are explicit and the build doesn't depend on their API level).
        const val EXTRA_MIN_LENGTH_MS = "android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS"
        const val EXTRA_COMPLETE_SILENCE_MS =
            "android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS"
        const val EXTRA_POSSIBLY_COMPLETE_SILENCE_MS =
            "android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS"
    }
}
