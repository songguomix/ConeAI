package com.cone.agent.assistant

/**
 * Cleans up raw speech-to-text output before it's shown or used. The bundled Chinese Vosk model
 * (and some system recognizers) put a space between every recognized token — "今天 天气 怎么 样" —
 * which reads wrong and hurts perceived accuracy. We tidy the spacing so Chinese comes out
 * contiguous while Latin words keep their spaces ("hello world" stays as-is).
 */

// A CJK ideograph (basic block + ext-A), used to decide where inter-character spaces are noise.
private const val CJK = "㐀-鿿"

// Space between two CJK characters — pure noise ("今天 天气" → "今天天气").
private val CJK_GAP = Regex("(?<=[$CJK])[ \t]+(?=[$CJK])")

// Space between a CJK character and an Arabic digit — Chinese runs them together ("我有 100 元").
private val CJK_DIGIT_GAP = Regex("(?<=[$CJK])[ \t]+(?=[0-9])|(?<=[0-9])[ \t]+(?=[$CJK])")

// Any run of whitespace collapses to a single space; leading/trailing trimmed.
private val MULTI_SPACE = Regex("[ \t]{2,}")

fun tidyTranscript(text: String): String =
    text.trim()
        .let { CJK_GAP.replace(it, "") }
        .let { CJK_DIGIT_GAP.replace(it, "") }
        .let { MULTI_SPACE.replace(it, " ") }
