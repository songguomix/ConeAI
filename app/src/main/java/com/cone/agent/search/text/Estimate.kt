package com.cone.agent.search.text

import kotlin.math.ceil

private val CJK_RE = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3040-\\u30FF]")
private val HANGUL_RE = Regex("[\\uAC00-\\uD7A3\\u3130-\\u318F]")
private val LATIN_RE = Regex("[A-Za-z\\u00C0-\\u024F]")
private val DIGIT_RE = Regex("[0-9]")

fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk=0; var hangul=0; var latin=0; var digit=0; var other=0
    for (ch in text) {
        val s = ch.toString()
        when {
            CJK_RE.containsMatchIn(s) -> cjk++
            HANGUL_RE.containsMatchIn(s) -> hangul++
            LATIN_RE.containsMatchIn(s) -> latin++
            DIGIT_RE.containsMatchIn(s) -> digit++
            else -> other++
        }
    }
    val est = cjk*1.0 + hangul*0.8 + latin/3.8 + digit/2.5 + other/3.0
    return ceil(est).toInt()
}

private const val ELLIPSIS = " …"

data class TruncateResult(val text: String, val truncated: Boolean, val tokens: Int)

fun truncateToTokens(text: String, maxTokens: Int): TruncateResult {
    val full = estimateTokens(text)
    if (full <= maxTokens) return TruncateResult(text, false, full)
    if (maxTokens <= 0) return TruncateResult("", true, 0)
    val target = maxTokens - estimateTokens(ELLIPSIS)
    if (target <= 0) return TruncateResult("", true, 0)
    var lo=0; var hi=text.length
    while (lo < hi) {
        val mid = (lo+hi+1)/2
        if (estimateTokens(text.substring(0, mid)) <= target) lo=mid else hi=mid-1
    }
    var cut = text.substring(0, lo)
    val boundary = lastIndexOfAny(cut, listOf("\n\n"))
        ?: lastIndexOfAny(cut, listOf("。","！","？",".\n",". ","! ","? ","\n"))
        ?: lastIndexOfAny(cut, listOf(" ","，","、",","))
    if (boundary != null && boundary > cut.length*0.66) cut = cut.substring(0, boundary)
    val out = cut.trimEnd() + ELLIPSIS
    return TruncateResult(out, true, estimateTokens(out))
}

private fun lastIndexOfAny(text: String, needles: List<String>): Int? {
    var best=-1
    for (n in needles) { val i=text.lastIndexOf(n); if (i>=0) best=maxOf(best, i+n.length) }
    return if (best>0) best else null
}
