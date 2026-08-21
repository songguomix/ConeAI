package com.cone.agent.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.cone.agent.BuildConfig
import com.cone.agent.R
import com.cone.agent.assistant.AssistantActivity
import com.cone.agent.assistant.VoskSpeechEngine
import com.cone.agent.core.Constants
import com.cone.agent.core.LocaleHelper
import com.cone.agent.core.Notifications
import com.cone.agent.core.WakeTuning
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * Always-on, on-device wake-word listener. Runs a continuous Vosk recognition loop in a foreground
 * (microphone-type) service and, when it hears the wake phrase, opens [AssistantActivity].
 *
 * Wake phrases — matched case/space-insensitively against partial & final transcripts:
 *   • "松果" (sōngguǒ = "pine cone"; reliable with the bundled Chinese model — say 松果 / 你好松果 / 嗨松果)
 *   • "pine cone" / "Hey pine cone" (works when an English/multilingual Vosk model is bundled)
 *
 * Mic etiquette ("互不冲突"): we capture from [MediaRecorder.AudioSource.VOICE_RECOGNITION], the
 * share-friendly, lowest-priority source, so the OS hands the mic to any other app that wants it and
 * silences us instead. We detect that silencing ([AudioRecordingConfiguration.isClientSilenced], API
 * 30+) and fully release our recorder, then periodically re-acquire — resuming only once no other app
 * is capturing. So we never block another app's microphone use.
 */
class WakeWordService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        // A mic foreground service started from the background (e.g. a START_STICKY restart) may be
        // disallowed; don't let that throw and crash — just stop quietly and resume on next app launch.
        if (runCatching { startForegroundCompat() }.isFailure) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running && hasMicPermission()) {
            // Seed the live sensitivity from the persisted value in case the system restarted this
            // sticky service without the UI (which sets it live) being open.
            sensitivity = WakeTuning.sensitivity(this)
            running = true
            worker = thread(name = "cone-wake", isDaemon = true) { listenLoop() }
        }
        // Sticky so the always-on listener comes back if the system reclaims the process.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = Notifications.build(
            this,
            Constants.CHANNEL_WAKE,
            LocaleHelper.string(this, R.string.notif_wake_title),
            LocaleHelper.string(this, R.string.notif_wake_text),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                Constants.NOTIF_WAKE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(Constants.NOTIF_WAKE_ID, notification)
        }
    }

    /* ---------------- the listening loop ---------------- */

    private fun listenLoop() {
        val model = runCatching { VoskSpeechEngine.obtainModel(this) }.getOrNull() ?: run {
            stopSelf(); return
        }
        val recognizer = runCatching { Recognizer(model, SAMPLE_RATE) }.getOrNull() ?: run {
            stopSelf(); return
        }
        // Phrase-biased keyword recognizer: a restricted grammar makes the decoder strongly favour the
        // wake phrases, dramatically raising the hit rate for "你好松果 / 松果". It can fail to build on
        // some models (lexicon/tokenization), so it's optional — the free-form recognizer is the safety
        // net and always runs, guaranteeing the wake word keeps working even if the grammar doesn't.
        val keyword = runCatching { Recognizer(model, SAMPLE_RATE, WAKE_GRAMMAR) }.getOrNull()
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        var lastTriggerMs = 0L

        try {
            while (running) {
                // Stand down while another component (the assistant) is capturing, and whenever the mic
                // permission is missing — don't fight for the mic or run a second recognizer concurrently.
                if (suspended || !hasMicPermission()) {
                    sleep(RETRY_MS)
                    continue
                }
                val audio = createRecorder()
                if (audio == null || audio.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { audio?.release() }
                    sleep(RETRY_MS)
                    continue
                }
                val sessionId = audio.audioSessionId
                val started = runCatching { audio.startRecording() }.isSuccess &&
                    audio.recordingState == AudioRecord.RECORDSTATE_RECORDING
                if (!started) {
                    runCatching { audio.release() }
                    sleep(RETRY_MS) // mic busy / another app has it — back off and retry
                    continue
                }

                recognizer.reset()
                keyword?.reset()
                val buffer = ShortArray(READ_CHUNK)
                var triggered = false
                var yielded = false
                while (running && !suspended) {
                    val read = audio.read(buffer, 0, buffer.size)
                    // A negative result is an AudioRecord error (ERROR_DEAD_OBJECT when the mic is revoked
                    // or taken by another app). Reading a dead recorder again aborts natively, so break
                    // out to release and re-acquire instead of looping on it.
                    if (read < 0) break
                    if (read == 0) { sleep(20); continue }
                    // Another app took priority and the OS muted us → release the mic for them.
                    if (isSilenced(audioManager, sessionId)) { yielded = true; break }

                    val freeText = if (recognizer.acceptWaveForm(buffer, read)) {
                        jsonField(recognizer.result, "text")
                    } else {
                        jsonField(recognizer.partialResult, "partial")
                    }
                    val kwText = keyword?.let {
                        if (it.acceptWaveForm(buffer, read)) jsonField(it.result, "text")
                        else jsonField(it.partialResult, "partial")
                    }.orEmpty()
                    // Tuning aid (`adb logcat -s ConeWakeWord`) — debug builds only: an always-on mic
                    // must never write transcripts of ambient speech into the system log in release.
                    if (BuildConfig.DEBUG && (freeText.isNotBlank() || kwText.isNotBlank())) {
                        Log.i(TAG, "heard kw='$kwText' ff='$freeText'")
                    }
                    if (matchesWakeWord(kwText) || matchesWakeWord(freeText)) {
                        val now = System.currentTimeMillis()
                        if (now - lastTriggerMs > TRIGGER_DEBOUNCE_MS) {
                            lastTriggerMs = now
                            triggered = true
                            launchAssistant()
                            break
                        }
                        recognizer.reset()
                        keyword?.reset()
                    }
                }

                runCatching { audio.stop() }
                runCatching { audio.release() }
                // After a hit, pause so the assistant we just opened can grab the mic; when yielding to
                // another app, poll back more quickly so we resume promptly once they're done.
                if (running) sleep(if (triggered) POST_TRIGGER_MS else if (yielded) YIELD_POLL_MS else RETRY_MS)
            }
        } finally {
            runCatching { recognizer.close() }
            runCatching { keyword?.close() }
        }
    }

    @SuppressLint("MissingPermission") // guarded by hasMicPermission() before the loop starts
    private fun createRecorder(): AudioRecord? {
        if (!hasMicPermission()) { stopSelf(); return null }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        return runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, READ_CHUNK * 2 * 2),
            )
        }.getOrNull()
    }

    /** True when our own capture is being silenced because another app is recording with priority. */
    private fun isSilenced(audioManager: AudioManager?, sessionId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || audioManager == null) return false
        return runCatching {
            audioManager.activeRecordingConfigurations
                .firstOrNull { it.clientAudioSessionId == sessionId }
                ?.isClientSilenced == true
        }.getOrDefault(false)
    }

    private fun launchAssistant() {
        // Launch ONLY the translucent assistant pill over whatever is on screen — do NOT surface the
        // Cone app itself. AssistantActivity has its own (empty) taskAffinity, and NEW_TASK without
        // CLEAR_TOP keeps the main task (MainActivity) in the background. NO_ANIMATION = instant overlay.
        // Background-activity-launch is permitted because the app holds SYSTEM_ALERT_WINDOW (overlay).
        val intent = Intent(this, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { startActivity(intent) }
    }

    /**
     * Wake-phrase match whose leniency is controlled by [sensitivity]. A small on-device model rarely
     * transcribes a casual "sōng-guǒ" as exactly 松果; it emits near-homophones (松过 / 送果 / 宋国 …) or
     * space-separated characters. We accept an adjacent "sōng-homophone + guǒ-homophone" pair, but how
     * wide those homophone sets are scales with sensitivity:
     *   • low  (≈0) — only the literal 松 + 果 (fewest false triggers);
     *   • mid       — adds the closest homophones;
     *   • high (≈1) — adds the looser homophones too (easiest to trigger).
     * English / pinyin models always match via "pinecone" / "songguo".
     */
    private fun matchesWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val latin = text.lowercase().filter { it in 'a'..'z' }
        if (latin.contains("pinecone") || latin.contains("songguo")) return true
        // Keep only CJK ideographs (drops the spaces a Chinese model puts between characters).
        val cjk = text.filter { it.code in 0x4E00..0x9FFF }
        val s = sensitivity
        val songSet = homophones(SONG_EXACT, SONG_NEAR, SONG_FAR, s)
        val guoSet = homophones(GUO_EXACT, GUO_NEAR, GUO_FAR, s)
        for (i in 0 until cjk.length - 1) {
            if (cjk[i] in songSet && cjk[i + 1] in guoSet) return true
        }
        return false
    }

    /** Builds the active homophone set for the current [sensitivity] from its tiered character sets. */
    private fun homophones(exact: Set<Char>, near: Set<Char>, far: Set<Char>, sensitivity: Float): Set<Char> =
        when {
            sensitivity < SENSITIVITY_LOW_MAX -> exact
            sensitivity < SENSITIVITY_MID_MAX -> exact + near
            else -> exact + near + far
        }

    private fun jsonField(json: String?, name: String): String =
        runCatching { JSONObject(json ?: "{}").optString(name, "") }.getOrDefault("").trim()

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    companion object {
        private const val TAG = "ConeWakeWord"
        private const val SAMPLE_RATE = 16000.0f
        private const val READ_CHUNK = 2400 // ~0.15s of 16 kHz mono audio per read (snappy partials)
        private const val TRIGGER_DEBOUNCE_MS = 4000L

        // Keyword-spotting grammar (JSON array): biases the decoder to the wake phrases; "[unk]" absorbs
        // all other speech so it doesn't false-fire. Multiple variants cover different Chinese
        // tokenizations — any out-of-vocabulary entry is simply ignored by Vosk.
        private const val WAKE_GRAMMAR =
            "[\"你好松果\",\"你好 松果\",\"你 好 松 果\",\"松果\",\"松 果\",\"嗨松果\",\"嗨 松果\",\"[unk]\"]"

        // Homophones of 松 (sōng) and 果 (guǒ) the small model is likely to emit for "松果", tiered from
        // exact → looser. Higher sensitivity unlocks the wider tiers (see [matchesWakeWord]).
        private val SONG_EXACT = setOf('松')
        private val SONG_NEAR = setOf('送', '宋')
        private val SONG_FAR = setOf('嵩', '颂')
        private val GUO_EXACT = setOf('果')
        private val GUO_NEAR = setOf('裹', '过', '锅')
        private val GUO_FAR = setOf('国', '郭')

        // Tier thresholds over the [0f, 1f] sensitivity scale: below LOW = exact only, below MID =
        // +near, otherwise +far. With the UI's 3-stop slider these map to 低 / 中 / 高.
        private const val SENSITIVITY_LOW_MAX = 0.34f
        private const val SENSITIVITY_MID_MAX = 0.67f

        /** Live wake sensitivity in [0f, 1f]; set by the UI on change and seeded from [WakeTuning]. */
        @Volatile
        var sensitivity: Float = Constants.DEFAULT_WAKE_SENSITIVITY
        private const val POST_TRIGGER_MS = 1500L
        private const val YIELD_POLL_MS = 600L
        private const val RETRY_MS = 1000L

        const val ACTION_STOP = "com.cone.agent.wake.STOP"

        /**
         * Set true while another component (the assistant) is actively using the mic, so the listener
         * stands down and we never run two recognizers / capture sessions at once.
         */
        @Volatile
        var suspended: Boolean = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WakeWordService::class.java)) }
        }
    }
}
