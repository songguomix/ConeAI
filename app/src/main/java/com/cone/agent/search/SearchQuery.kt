package com.cone.agent.search

import com.cone.agent.search.text.ftsQuote
import com.cone.agent.search.text.queryTerms
import com.cone.agent.search.text.tokenize

data class ParsedQuery(
    val text: String,
    val terms: List<String>,
    val phrases: List<String>,
    val negations: List<String>,
    val site: List<String>,
    val filetype: List<String>,
    val after: String?,
    val before: String?,
    val isQuestion: Boolean,
    val raw: String
)

private val OPERATOR_RE = Regex("\\b(site|host|filetype|ext|after|since|before|until|collection|lang)\\s*:\\s*(\"[^\"]+\"|\\S+)", RegexOption.IGNORE_CASE)
private val QUESTION_RE = Regex("^(how|what|why|when|where|which|who|can|does|do|is|are|should|could|would|will)\\b|[?？]\\s*$|怎么|如何|为什么|是什么|什么是|哪些|能否|可以吗", RegexOption.IGNORE_CASE)

fun parseQuery(raw: String): ParsedQuery {
    var text = raw.trim()
    val phrases = mutableListOf<String>()
    text = text.replace(Regex("\"([^\"]{2,})\"")) { m -> phrases.add(m.groupValues[1].trim()); " " }
    val site = mutableListOf<String>()
    val filetype = mutableListOf<String>()
    var after: String? = null
    var before: String? = null
    text = OPERATOR_RE.replace(text) { m ->
        val key = m.groupValues[1].lowercase()
        val v = m.groupValues[2].replace(Regex("^\"|\"$"), "").trim()
        when (key) {
            "site","host" -> site.add(v.replace(Regex("^https?://"), "").replace(Regex("/.*$"), "").lowercase())
            "filetype","ext" -> filetype.add(v.replace(Regex("^\\."), "").lowercase())
            "after","since" -> after = normalizeDate(v)
            "before","until" -> before = normalizeDate(v)
        }
        " "
    }
    val negations = mutableListOf<String>()
    text = text.replace(Regex("(^|\\s)-([^\\s-]{2,})")) { m -> negations.add(m.groupValues[2].lowercase()); m.groupValues[1] }
    text = text.replace(Regex("\\s+"), " ").trim()
    return ParsedQuery(text, queryTerms(listOf(text).plus(phrases).joinToString(" ")), phrases, negations, site, filetype, after, before, QUESTION_RE.containsMatchIn(raw.trim()), raw)
}

private fun normalizeDate(v: String): String? {
    Regex("^(\\d+)\\s*(d|w|m|y|day|days|week|weeks|month|months|year|years)$", RegexOption.IGNORE_CASE).find(v)?.let {
        val n = it.groupValues[1].toInt(); val unit = it.groupValues[2].lowercase()[0]
        val days = when(unit){ 'd'->n; 'w'->n*7; 'm'->n*30; else->n*365 }
        return java.time.Instant.now().minusSeconds(days*86400L).toString()
    }
    return try { java.time.Instant.parse(v).toString() } catch(_:Exception){ try{ java.util.Date(v).toInstant().toString()}catch(_:Exception){null} }
}

fun buildMatchExpression(q: ParsedQuery): String? {
    val clauses = mutableListOf<String>()
    for (phrase in q.phrases) {
        val toks = tokenize(phrase, unigrams=false, dropStopwords=false)
        if (toks.isEmpty()) continue
        clauses.add("(${toks.joinToString(" + ") { ftsQuote(it) }})")
    }
    val bare = tokenize(q.text, unigrams=false)
    val unique = bare.distinct()
    if (unique.isNotEmpty()) {
        val capped = unique.take(64)
        clauses.add("(${capped.joinToString(" OR ") { ftsQuote(it) }})")
    }
    if (clauses.isEmpty()) return null
    return clauses.joinToString(" AND ")
}

fun expandQuery(q: ParsedQuery, limit:Int=3): List<String> {
    val out = linkedSetOf<String>()
    val base = q.text.lowercase()
    val stripped = base.replace(Regex("^(how (do|can|to|does|should) (i|you|we|one)?|what (is|are|does)|why (do|does|is)|when (do|does|should)|where (do|does|can)|请问|我想知道|怎么样才能|如何才能)\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("[?？。]+$"), "").trim()
    if (stripped.isNotEmpty() && stripped != base) out.add(stripped)
    val variants = base.replace(Regex("\\bnode\\.js\\b"), "nodejs").replace(Regex("\\bnodejs\\b"), "node.js").replace(Regex("\\bjs\\b"), "javascript").replace(Regex("\\bts\\b"), "typescript").replace(Regex("\\bk8s\\b"), "kubernetes").replace(Regex("\\bconfig\\b"), "configuration")
    if (variants != base) out.add(variants)
    if (q.terms.size > 5) out.add(q.terms.take(4).joinToString(" "))
    return out.take(limit)
}
