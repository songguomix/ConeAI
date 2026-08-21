package com.cone.agent.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks answers aloud, automatically discovering a TTS engine on the phone that can actually handle
 * the current locale. The system default engine frequently lacks the Chinese voice, so we probe every
 * installed engine ([TextToSpeech.getEngines]) and switch to the first that reports the language as
 * available; if none do, we still speak with whatever the default can manage rather than going silent.
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val locale: Locale = Locale.getDefault()
    private val tried = HashSet<String>()

    /** Begin initializing (and engine-probing) now so a voice is ready by the time we speak. */
    fun warmUp() = ensure(null)

    fun speak(text: String) {
        // Strip markdown asterisks and emoji so the voice doesn't read "星号 星号" / emoji names aloud.
        val spoken = sanitizeForSpeech(text)
        if (spoken.isBlank()) return
        val engine = tts
        if (engine != null && ready) {
            engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else {
            pending = spoken
            ensure(null)
        }
    }

    /**
     * Removes content that reads badly aloud: markdown emphasis asterisks (`*`, `**`) and emoji /
     * pictographs / symbols, then collapses the whitespace they leave behind. The on-screen answer is
     * untouched — only the spoken copy is cleaned.
     */
    private fun sanitizeForSpeech(raw: String): String =
        raw.replace(EMOJI_REGEX, "")
            .replace("*", "")
            .replace(Regex("[ \\t\\u00A0]{2,}"), " ")
            .trim()

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        pending = null
    }

    /** (Re)create the engine. [enginePackage] null = system default; otherwise a specific engine. */
    private fun ensure(enginePackage: String?) {
        if (enginePackage == null && tts != null) return
        runCatching { tts?.shutdown() }
        ready = false
        val listener = TextToSpeech.OnInitListener { status -> onInit(status) }
        tts = runCatching {
            if (enginePackage != null) TextToSpeech(context, listener, enginePackage)
            else TextToSpeech(context, listener)
        }.getOrNull()
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        // Remember which engine this instance is so we don't probe it again.
        runCatching { engine.defaultEngine }.getOrNull()?.let { tried.add(it) }

        if (status == TextToSpeech.SUCCESS && supportsLocale(engine)) {
            runCatching { engine.language = locale }
            markReady()
            return
        }
        // This engine can't speak our language (or failed to init) — try the next installed one.
        if (tryNextEngine(engine)) return
        // No better engine: still mark ready if it at least initialized, so we say *something*.
        if (status == TextToSpeech.SUCCESS) markReady()
    }

    private fun markReady() {
        ready = true
        pending?.let { speak(it); pending = null }
    }

    private fun supportsLocale(engine: TextToSpeech): Boolean {
        val res = runCatching { engine.isLanguageAvailable(locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return res == TextToSpeech.LANG_AVAILABLE ||
            res == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            res == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    private fun tryNextEngine(engine: TextToSpeech): Boolean {
        val installed = runCatching { engine.engines.mapNotNull { it.name } }.getOrDefault(emptyList())
        val next = installed.firstOrNull { it.isNotBlank() && it !in tried } ?: return false
        ensure(next)
        return true
    }

    private companion object {
        const val UTTERANCE_ID = "cone-answer"

        // Common emoji / pictograph / symbol blocks + ZWJ + variation selectors (surrogate-aware via \x{}).
        val EMOJI_REGEX = Regex(
            "[\\x{1F000}-\\x{1FAFF}" + // emoji, pictographs, symbols (supplemental planes)
                "\\x{2600}-\\x{27BF}" + // misc symbols + dingbats
                "\\x{2B00}-\\x{2BFF}" + // misc symbols & arrows (⭐ etc.)
                "\\x{2300}-\\x{23FF}" + // misc technical (⏰ ⏸ etc.)
                "\\x{2190}-\\x{21FF}" + // arrows
                "\\x{FE00}-\\x{FE0F}" + // variation selectors
                "\\x{200D}\\x{20E3}]",   // zero-width joiner + combining enclosing keycap
        )
    }
}
