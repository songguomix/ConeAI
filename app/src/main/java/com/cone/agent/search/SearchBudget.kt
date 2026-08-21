package com.cone.agent.search

import com.cone.agent.search.text.estimateTokens
import com.cone.agent.search.text.truncateToTokens

data class PackedResult(val candidate: ScoredCandidate, val content: String, val tokens: Int, val truncated: Boolean)
data class PackReport(val results: List<PackedResult>, val tokensUsed: Int, val tokensBudget: Int, val resultsDropped: Int, val resultsTruncated: Int)

fun packToBudget(candidates: List<ScoredCandidate>, maxTokens:Int, minTokensPerResult:Int=60, maxTokensPerResult:Int=1200, overheadPerResult:Int=28): PackReport {
    if(candidates.isEmpty()) return PackReport(emptyList(),0,maxTokens,0,0)
    val perFloor = minTokensPerResult+overheadPerResult
    val affordable = maxOf(1, minOf(candidates.size, maxTokens/perFloor))
    val kept = candidates.take(affordable)
    val weights = kept.mapIndexed { i,c -> (1.0/ kotlin.math.sqrt((i+1).toDouble())) * maxOf(0.15,c.score) }
    val sum = weights.sum().takeIf{it!=0.0}?:1.0
    val contentBudget = maxOf(0, maxTokens - kept.size*overheadPerResult)
    val alloc = weights.map{ w -> maxOf(minTokensPerResult, minOf(maxTokensPerResult, ((w/sum)*contentBudget).toInt())) }.toMutableList()
    var surplus=0; val needs=kept.mapIndexed{i,c-> val need=estimateTokens(c.content); if(need<alloc[i]) surplus+=alloc[i]-need; need }
    for(i in kept.indices){ if(surplus<=0) break; val deficit=needs[i]-alloc[i]; if(deficit>0){ val give=minOf(deficit,surplus, maxTokensPerResult-alloc[i]); alloc[i]+=give; surplus-=give } }
    val results=mutableListOf<PackedResult>(); var used=0; var trunc=0
    for(i in kept.indices){
        val remaining = maxTokens-used; if(remaining < perFloor) break
        val allow = minOf(alloc[i], remaining-overheadPerResult)
        val r = truncateToTokens(kept[i].content, allow)
        if(r.tokens < minTokensPerResult && r.truncated) break
        if(r.truncated) trunc++
        used += r.tokens + overheadPerResult
        results.add(PackedResult(kept[i], r.text, r.tokens, r.truncated))
    }
    return PackReport(results, used, maxTokens, candidates.size-results.size, trunc)
}

fun bestSnippet(content:String, terms:List<String>, maxTokens:Int): String {
    if(estimateTokens(content) <= maxTokens) return content
    val sentences = content.split(Regex("(?<=[.!?。！？\\n])\\s+")).filter{it.trim().isNotEmpty()}
    if(sentences.size<=1) return truncateToTokens(content,maxTokens).text
    val lower = sentences.map{it.lowercase()}; val hits = lower.map{ s-> terms.count{ s.contains(it) } }
    var bestStart=0; var bestScore=-1
    for(start in sentences.indices){
        var tokens=0; var score=0; var end=start
        while(end<sentences.size){ val t=estimateTokens(sentences[end]); if(tokens+t>maxTokens) break; tokens+=t; score+=hits[end]; end++ }
        if(score>bestScore){ bestScore=score; bestStart=start }
        if(end>=sentences.size) break
    }
    val out=mutableListOf<String>(); var tokens=0
    for(i in bestStart until sentences.size){ val t=estimateTokens(sentences[i]); if(tokens+t>maxTokens) break; out.add(sentences[i]); tokens+=t }
    val prefix = if(bestStart>0) "… " else ""; val suffix = if(bestStart+out.size<sentences.size) " …" else ""
    return prefix + out.joinToString(" ").trim() + suffix
}
