package com.cone.agent.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Fully **offline / on-device** speech-to-text via [Vosk](https://alphacephei.com/vosk). No network,
 * no cloud, no Google services — it runs a local model bundled in `assets/vosk-model/`. If that model
 * isn't present the engine reports unavailable (see [isModelAvailable]) and the caller falls back to
 * the system recognizer / cloud / typing.
 *
 * We copy the model out of assets ourselves (Vosk's StorageService.unpack requires a `uuid` file the
 * downloaded models don't ship, which caused "vosk-model/uuid" load failures).
 *
 * To enable: download a Vosk model (e.g. `vosk-model-small-cn-0.22`, ~42 MB, from
 * https://alphacephei.com/vosk/models) and unzip its **contents** into `app/src/main/assets/vosk-model/`
 * so that `assets/vosk-model/conf/`, `assets/vosk-model/am/`, … exist. (`./setup.sh` does this.)
 */
class VoskSpeechEngine(private val context: Context) {

    interface Callbacks {
        /** The engine finished loading and is now actually listening. */
        fun onReady() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onError(message: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var service: SpeechService? = null
    private var callbacks: Callbacks? = null
    private var released = false
    private val transcript = StringBuilder()

    // Auto end-pointing: once speech has been heard, a pause longer than SILENCE_MS finalizes the
    // utterance on its own (SpeechService.stop → onFinalResult), so the caller can auto-send without
    // a manual "stop" tap. Unlike the system recognizer, Vosk listens continuously and never ends by
    // itself, so we detect the silence here. Off by default; the assistant popup opts in.
    private var autoEndpoint = false
    private var lastHeard = ""
    private var hasSpoken = false
    private val endpointRunnable = Runnable { if (hasSpoken && !released) stop() }

    fun start(callbacks: Callbacks, autoEndpoint: Boolean = false) {
        this.callbacks = callbacks
        this.autoEndpoint = autoEndpoint
        released = false
        transcript.setLength(0)
        lastHeard = ""
        hasSpoken = false
        // Load the (process-shared) model off the main thread — the first run copies ~40 MB — then
        // start listening back on the main thread.
        ioExecutor.execute {
            val result = runCatching { loadSharedModel(context) }
            mainHandler.post {
                if (released) return@post
                result
                    .onSuccess { m -> beginListening(m) }
                    .onFailure { e ->
                        callbacks?.onError(LocaleHelper.string(context, R.string.vosk_load_failed, e.message ?: ""))
                    }
            }
        }
    }

    private fun beginListening(model: Model) {
        if (released) return
        runCatching {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            val svc = SpeechService(recognizer, SAMPLE_RATE).also { service = it }
            svc.startListening(listener)
            callbacks?.onReady()
        }.onFailure { callbacks?.onError(LocaleHelper.string(context, R.string.vosk_start_failed, it.message ?: "")) }
    }

    /** Finalize what's been heard so far (user tapped the mic to stop, or silence end-pointing). */
    fun stop() {
        mainHandler.removeCallbacks(endpointRunnable)
        runCatching { service?.stop() }
    }

    /**
     * (Re)arm the silence timer whenever the heard text changes. After [SILENCE_MS] with no further
     * speech — and only once something has actually been said — finalize the utterance.
     */
    private fun scheduleEndpoint(text: String) {
        if (!autoEndpoint || text == lastHeard) return
        lastHeard = text
        if (text.isNotBlank()) hasSpoken = true
        mainHandler.removeCallbacks(endpointRunnable)
        if (hasSpoken) mainHandler.postDelayed(endpointRunnable, SILENCE_MS)
    }

    /**
     * Clears the accumulated transcript so subsequent speech starts a fresh segment. Used when the
     * user manually edits the field mid-listening: their edit becomes the new base and later speech
     * appends after it instead of re-emitting the already-typed words.
     */
    fun resetTranscript() {
        transcript.setLength(0)
    }

    fun destroy() {
        released = true
        mainHandler.removeCallbacks(endpointRunnable)
        val svc = service
        service = null
        callbacks = null
        runCatching { svc?.stop() }
        runCatching { svc?.shutdown() }
        // The shared model is kept loaded for the process; we only release this session's recorder.
        runCatching { ioExecutor.shutdownNow() }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val live = field(hypothesis, "partial")
            val shown = (transcript.toString() + " " + live).trim()
            if (shown.isNotBlank()) {
                callbacks?.onPartial(tidyTranscript(shown))
                scheduleEndpoint(shown)
            }
        }

        override fun onResult(hypothesis: String?) {
            val seg = field(hypothesis, "text")
            if (seg.isNotBlank()) {
                if (transcript.isNotEmpty()) transcript.append(' ')
                transcript.append(seg)
                callbacks?.onPartial(tidyTranscript(transcript.toString()))
                scheduleEndpoint(transcript.toString())
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            val seg = field(hypothesis, "text")
            if (seg.isNotBlank()) {
                if (transcript.isNotEmpty()) transcript.append(' ')
                transcript.append(seg)
            }
            callbacks?.onFinal(tidyTranscript(transcript.toString()))
        }

        override fun onError(e: Exception?) {
            callbacks?.onError(e?.message ?: LocaleHelper.string(context, R.string.vosk_error))
        }

        override fun onTimeout() {
            callbacks?.onFinal(tidyTranscript(transcript.toString()))
        }
    }

    private fun field(json: String?, name: String): String =
        runCatching { JSONObject(json ?: "{}").optString(name, "") }.getOrDefault("").trim()

    companion object {
        private const val ASSET_DIR = "vosk-model"
        private const val TARGET_DIR = "vosk-model"
        private const val SAMPLE_RATE = 16000.0f

        // Silence after speech before we auto-finalize (assistant popup only). Long enough to allow a
        // natural mid-sentence pause, short enough to feel like it sends as soon as you're done.
        private const val SILENCE_MS = 1500L

        @Volatile private var sharedModel: Model? = null

        /** Public accessor so other components (e.g. the wake-word service) can reuse the loaded model. */
        fun obtainModel(context: Context): Model = loadSharedModel(context)

        /** Loads the model once per process: copy assets → internal storage (cached), then [Model]. */
        @Synchronized
        private fun loadSharedModel(context: Context): Model {
            sharedModel?.let { return it }
            val dir = unpackModel(context)
            return Model(dir.absolutePath).also { sharedModel = it }
        }

        /** Copies `assets/vosk-model/` into internal storage once (idempotent) and returns the dir. */
        private fun unpackModel(context: Context): File {
            val target = File(context.filesDir, TARGET_DIR)
            val marker = File(target, ".installed")
            if (!marker.exists()) {
                target.deleteRecursively()
                copyAsset(context, ASSET_DIR, target)
                marker.writeText("ok")
            }
            return target
        }

        private fun copyAsset(context: Context, assetPath: String, target: File) {
            val children = runCatching { context.assets.list(assetPath) }.getOrNull()
            if (children.isNullOrEmpty()) {
                // A file — copy its bytes.
                target.parentFile?.mkdirs()
                runCatching {
                    context.assets.open(assetPath).use { input ->
                        FileOutputStream(target).use { input.copyTo(it) }
                    }
                }
            } else {
                target.mkdirs()
                for (child in children) {
                    copyAsset(context, "$assetPath/$child", File(target, child))
                }
            }
        }

        /** True only when a real Vosk model (not just the placeholder README) is bundled in assets. */
        fun isModelAvailable(context: Context): Boolean = runCatching {
            val entries = context.assets.list(ASSET_DIR)?.toSet().orEmpty()
            "conf" in entries || "am" in entries || "graph" in entries
        }.getOrDefault(false)
    }
}
