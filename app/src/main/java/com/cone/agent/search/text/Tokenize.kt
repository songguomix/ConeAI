package com.cone.agent.search.text

import java.text.Normalizer

private val CJK_REGEX = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3040-\\u30FF\\uAC00-\\uD7AF]")
private val LATIN_WORD = Regex("[a-z0-9][a-z0-9'’+#._-]*")

private val STOPWORDS = setOf(
    "a","an","and","are","as","at","be","but","by","for","from","how",
    "i","in","is","it","its","of","on","or","that","the","then","there","these","this","to","was","were","what","when","where","which","who","why","will","with","you","your",
    "的","了","和","是","在","我","有","就","不","人","都","一","一个","上","也","很","到","说","要","去","会","着","没有","看","好","自己","这","那","吗","呢","吧"
)

fun normalize(input: String): String {
    var s = Normalizer.normalize(input, Normalizer.Form.NFKC).lowercase()
    s = s.replace(Regex("[\\u3000-\\u303F\\uFF01-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF65]"), " ")
    s = s.replace(Regex("\\s+"), " ").trim()
    return s
}

fun isCJK(ch: String) = CJK_REGEX.containsMatchIn(ch)

data class Segment(val cjk: Boolean, val text: String)

private fun segments(text: String): List<Segment> {
    val out = mutableListOf<Segment>()
    var buf = StringBuilder()
    var bufCJK: Boolean? = null
    for (ch in text) {
        val cjk = CJK_REGEX.matches(ch.toString())
        if (bufCJK == null || cjk == bufCJK) {
            buf.append(ch); bufCJK = cjk
        } else {
            out.add(Segment(bufCJK!!, buf.toString())); buf = StringBuilder().append(ch); bufCJK = cjk
        }
    }
    if (buf.isNotEmpty() && bufCJK != null) out.add(Segment(bufCJK, buf.toString()))
    return out
}

fun tokenize(input: String, unigrams: Boolean = true, dropStopwords: Boolean = true): List<String> {
    val text = normalize(input)
    val tokens = mutableListOf<String>()
    for (seg in segments(text)) {
        if (seg.cjk) {
            val chars = seg.text.toList().map { it.toString() }
            if (chars.size == 1) { tokens.add(chars[0]); continue }
            for (i in 0 until chars.size - 1) tokens.add(chars[i] + chars[i+1])
            if (unigrams) chars.forEach { tokens.add(it) }
        } else {
            LATIN_WORD.findAll(seg.text).forEach { m ->
                var w = m.value
                w = w.replace(Regex("^[._-]+|[._-]+$"), "")
                if (w.isNotEmpty()) tokens.add(w)
            }
        }
    }
    return if (dropStopwords) tokens.filter { it !in STOPWORDS } else tokens
}

fun toIndexString(input: String) = tokenize(input).joinToString(" ")

fun queryTerms(query: String): List<String> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<String>()
    for (t in tokenize(query, unigrams = false)) if (t.isNotEmpty() && seen.add(t)) out.add(t)
    return out
}

fun ftsQuote(token: String) = "\"${token.replace("\"","\"\"")}\""
