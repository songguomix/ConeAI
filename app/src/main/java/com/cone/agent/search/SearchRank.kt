package com.cone.agent.search

import com.cone.agent.search.text.normalize
import com.cone.agent.search.extract.hammingDistance
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class Candidate(
    val chunkId: Long, val docId: Long, val url: String, val title: String, val description: String,
    val host: String, val headingPath: String, val anchor: String, val content: String,
    val tokenCount: Int, val simhash: String, val quality: Double, val publishedAt: String?, val fetchedAt: String, val ordinal: Int, val bm25: Double
)
data class Signals(val lexical: Double, val coverage: Double, val proximity: Double, val heading: Double, val quality: Double, val substance: Double, val freshness: Double, val position: Double, val diversity: Double)
data class ScoredCandidate(val candidate: Candidate, val score: Double, val signals: Signals) {
    val chunkId get()=candidate.chunkId; val docId get()=candidate.docId; val url get()=candidate.url; val title get()=candidate.title; val description get()=candidate.description; val host get()=candidate.host; val headingPath get()=candidate.headingPath; val anchor get()=candidate.anchor; val content get()=candidate.content; val tokenCount get()=candidate.tokenCount; val simhash get()=candidate.simhash; val quality get()=candidate.quality; val publishedAt get()=candidate.publishedAt; val fetchedAt get()=candidate.fetchedAt; val ordinal get()=candidate.ordinal; val bm25 get()=candidate.bm25
}

private val WEIGHTS = mapOf("lexical" to 1.0,"coverage" to 0.85,"proximity" to 0.35,"heading" to 0.4,"quality" to 0.3,"substance" to 0.55,"freshness" to 0.25,"position" to 0.15)

private fun normalizeBm25(values: List<Double>): (Double)->Double {
    val pos = values.map{-it}; val mx = (pos.maxOrNull()?:1e-6).coerceAtLeast(1e-6); val mn = min(pos.minOrNull()?:0.0,0.0); val span = (mx-mn).takeIf{it!=0.0}?:1.0
    return { v -> max(0.0, min(1.0, (-v - mn)/span)) }
}
private fun coverage(text: String, terms: List<String>): Double { if(terms.isEmpty()) return 0.5; var h=0; for(t in terms) if(text.contains(t)) h++; return h.toDouble()/terms.size }
private fun proximity(text: String, terms: List<String>): Double { if(terms.size<2) return 0.5; val pos = terms.mapNotNull{ val i=text.indexOf(it); if(i>=0) i else null }; if(pos.size<2) return 0.0; val s=pos.sorted(); val span=s.last()-s.first(); val ideal=terms.joinToString("").length; return max(0.0,min(1.0, ideal.toDouble()/max(span,ideal))) }
private fun substance(content: String): Double {
    val lines = content.split("\n").filter{it.trim().isNotEmpty()}; if(lines.isEmpty()) return 0.0
    val headingLines = lines.count{ Regex("^#{1,6}\\s").containsMatchIn(it) }
    val linkOnly = lines.count{ Regex("^\\s*[-*]?\\s*\\[[^\\]]*\\]\\([^)]*\\)\\s*$").matches(it) }
    val proseChars = lines.filter{ !Regex("^#{1,6}\\s").containsMatchIn(it) }.joinToString(" ").replace(Regex("\\[[^\\]]*\\]\\([^)]*\\)"), "").trim().length
    var s=1.0; val hr=headingLines.toDouble()/lines.size; if(hr>0.3) s-=(hr-0.3)*1.4; s-= (linkOnly.toDouble()/lines.size)*0.8
    if(headingLines>0){ val per=proseChars.toDouble()/headingLines; if(per<120) s-=0.3*(1-per/120) }
    if(content.contains("```")) s+=0.25
    return max(0.0,min(1.0,s))
}
private fun freshness(publishedAt: String?, fetchedAt: String): Double {
    val ref = publishedAt ?: fetchedAt; val t = try{ java.time.Instant.parse(ref).toEpochMilli() }catch(_:Exception){ try{ java.util.Date(ref).time }catch(_:Exception){ return 0.4 } }
    val days = (System.currentTimeMillis()-t)/86400000.0; if(days<0) return 0.8; return max(0.05,min(1.0, exp(-days/520)))
}
private fun round3(n:Double)= kotlin.math.round(n*1000)/1000.0

fun scoreCandidates(candidates: List<Candidate>, q: ParsedQuery): List<ScoredCandidate> {
    if(candidates.isEmpty()) return emptyList()
    val toLexical = normalizeBm25(candidates.map{it.bm25})
    val terms = q.terms
    return candidates.map { c ->
        val body = normalize("${c.headingPath} ${c.content}")
        val head = normalize("${c.title} ${c.headingPath}")
        val lexical = toLexical(c.bm25); val cov=coverage(body,terms); val prox=proximity(body,terms); val headingHit=coverage(head,terms); val subst=substance(c.content); val fresh=freshness(c.publishedAt,c.fetchedAt); val position= if(c.ordinal==0) 1.0 else max(0.0,1-c.ordinal/40.0)
        var score = WEIGHTS["lexical"]!!*lexical + WEIGHTS["coverage"]!!*cov + WEIGHTS["proximity"]!!*prox + WEIGHTS["heading"]!!*headingHit + WEIGHTS["quality"]!!*c.quality + WEIGHTS["substance"]!!*subst + WEIGHTS["freshness"]!!*fresh + WEIGHTS["position"]!!*position
        for(p in q.phrases) if(!body.contains(normalize(p))) score-=1.2
        for(n in q.negations) if(body.contains(normalize(n))) score-=1.5
        if(c.tokenCount<40) score-=0.35
        ScoredCandidate(c, score, Signals(round3(lexical),round3(cov),round3(prox),round3(headingHit),round3(c.quality),round3(subst),round3(fresh),round3(position),1.0))
    }.sortedByDescending{it.score}
}

fun diversify(scored: List<ScoredCandidate>, limit:Int, lambda:Double=0.35, perDocument:Int=2, perHost:Int=4, nearDuplicateDistance:Int=6): List<ScoredCandidate> {
    val selected=mutableListOf<ScoredCandidate>(); val docCount=mutableMapOf<Long,Int>(); val hostCount=mutableMapOf<String,Int>(); val pool=scored.toMutableList()
    while(selected.size<limit && pool.isNotEmpty()){
        var bestIdx=-1; var bestVal=Double.NEGATIVE_INFINITY
        for(i in pool.indices){
            val c=pool[i]
            if((docCount[c.docId]?:0)>=perDocument) continue
            if((hostCount[c.host]?:0)>=perHost) continue
            if(selected.any{ hammingDistance(c.simhash,it.simhash) <= nearDuplicateDistance }) continue
            var maxSim=0.0; for(s in selected){ val d=hammingDistance(c.simhash,s.simhash); val sim=1-d/64.0; if(sim>maxSim) maxSim=sim }
            val mmr=(1-lambda)*c.score - lambda*maxSim*2
            if(mmr>bestVal){ bestVal=mmr; bestIdx=i }
        }
        if(bestIdx==-1) break
        val chosen=pool.removeAt(bestIdx)
        val div = if(selected.isEmpty()) 1.0 else 1 - selected.maxOf{ 1 - hammingDistance(chosen.simhash,it.simhash)/64.0 }
        val withDiv = chosen.copy(signals=chosen.signals.copy(diversity=round3(div)))
        selected.add(withDiv)
        docCount[chosen.docId]=(docCount[chosen.docId]?:0)+1
        hostCount[chosen.host]=(hostCount[chosen.host]?:0)+1
    }
    return selected
}
