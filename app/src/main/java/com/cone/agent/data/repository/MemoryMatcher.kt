package com.cone.agent.data.repository

/**
 * How memory text is compared — both for "which facts does this question need?" and for "have we
 * already been told this?".
 *
 * Chinese has no spaces, so the scoring this replaces compared **sets of single characters** and
 * counted the matches raw. Two things went wrong with that, and both showed up immediately on
 * ordinary data:
 *
 *  - *Noise.* 「用户每天早上七点跑步」 outranked 「用户不吃香菜」 for the question 「我今天想吃点什么好呢」,
 *    because 天 and 点 happen to appear in both. A question about dinner led with the user's jogging
 *    routine.
 *  - *Length bias.* The score was an unnormalised count, so a rambling memory had more characters
 *    available to collide with and drifted to the top of every ranking regardless of topic.
 *
 * Adjacent-character bigrams fix the noise — 香菜 only matches text that actually says 香菜 — but on
 * their own they are too brittle for short questions, where the useful evidence is often a single
 * shared character. So both are kept and a bigram simply counts for much more, and every score is a
 * fraction of the text's own weight, which is what makes a six-character fact and a thirty-character
 * one comparable at all.
 */
object MemoryMatcher {

    /**
     * The text as weighted tokens: CJK bigrams, the same run's single characters at a lower weight,
     * and Latin/digit words (already meaningful units, so they score like bigrams).
     *
     * The 「用户」 every extracted fact opens with is dropped: it appears in all of them, so it can
     * never tell two memories apart, and it would otherwise pad every denominator.
     */
    fun weightedTokens(text: String): Map<String, Double> {
        val out = HashMap<String, Double>()
        val cjk = StringBuilder()
        val latin = StringBuilder()

        fun put(token: String, weight: Double) {
            val existing = out[token]
            if (existing == null || existing < weight) out[token] = weight
        }

        fun flushCjk() {
            when {
                cjk.isEmpty() -> Unit
                cjk.length == 1 -> put(cjk.toString(), UNIGRAM_WEIGHT)
                else -> {
                    for (i in cjk.indices) put(cjk[i].toString(), UNIGRAM_WEIGHT)
                    for (i in 0 until cjk.length - 1) put(cjk.substring(i, i + 2), BIGRAM_WEIGHT)
                }
            }
            cjk.setLength(0)
        }

        fun flushLatin() {
            if (latin.length >= 2) put(latin.toString(), BIGRAM_WEIGHT)
            latin.setLength(0)
        }

        for (raw in stripSubject(text)) {
            val c = raw.lowercaseChar()
            when {
                isCjk(c) -> {
                    flushLatin()
                    cjk.append(c)
                }
                c.isLetterOrDigit() -> {
                    flushCjk()
                    latin.append(c)
                }
                else -> {
                    flushCjk()
                    flushLatin()
                }
            }
        }
        flushCjk()
        flushLatin()
        return out
    }

    /**
     * How much of [memory] the [query] actually touches, in 0..1.
     *
     * Deliberately asymmetric: a short question can only ever mention a fraction of a long memory,
     * and dividing by the memory's own weight is what stops verbose facts from outranking precise
     * ones.
     */
    fun coverage(query: String, memory: String): Double {
        val mem = weightedTokens(memory)
        if (mem.isEmpty()) return 0.0
        val q = weightedTokens(query)
        if (q.isEmpty()) return 0.0
        val total = mem.values.sum()
        if (total <= 0.0) return 0.0
        return mem.entries.sumOf { (token, weight) -> if (token in q) weight else 0.0 } / total
    }

    /**
     * How completely the *smaller* of the two texts sits inside the other, in 0..1 — the question
     * "is one of these just a more detailed way of saying the other?".
     *
     * Containment rather than Jaccard, because that is the shape the duplicate actually takes:
     * 「用户喜欢喝咖啡」 followed later by 「用户喜欢喝手冲咖啡，不加糖」. Jaccard scores that pair low
     * (the longer text has much the larger vocabulary) and would keep both, while containment sees
     * the first sitting entirely inside the second. Crucially it still separates the pairs that must
     * never merge — 花生过敏 / 海鲜过敏, 女儿 / 儿子, 北京 / 上海 — which differ in exactly the tokens
     * that carry the meaning.
     */
    fun containment(a: String, b: String): Double {
        val ta = weightedTokens(a)
        val tb = weightedTokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val smaller = minOf(ta.values.sum(), tb.values.sum())
        if (smaller <= 0.0) return 0.0
        val intersection = ta.entries.sumOf { (token, weight) -> minOf(weight, tb[token] ?: 0.0) }
        return intersection / smaller
    }

    /** Facts are stored third-person ("用户…"); that prefix carries no distinguishing information. */
    private fun stripSubject(text: String): String {
        val s = text.trim()
        for (prefix in SUBJECT_PREFIXES) {
            if (s.startsWith(prefix, ignoreCase = true)) return s.removePrefix(prefix)
        }
        return s
    }

    private fun isCjk(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿'

    /** A shared bigram is strong evidence; a shared character on its own is a hint. */
    private const val BIGRAM_WEIGHT = 1.0
    private const val UNIGRAM_WEIGHT = 0.35

    private val SUBJECT_PREFIXES = listOf("用户的", "用户", "the user's", "the user", "user's", "user")
}
