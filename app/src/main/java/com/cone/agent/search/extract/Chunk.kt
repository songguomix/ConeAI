package com.cone.agent.search.extract

import com.cone.agent.search.text.estimateTokens
import com.cone.agent.search.text.tokenize
import java.security.MessageDigest

data class Chunk(val ordinal:Int, val anchor:String, val headingPath:String, val content:String, val tokenCount:Int, val charStart:Int, val charEnd:Int, val simhash:String, val symbol:String? = null)

fun simhash(text:String): String {
    val tokens = tokenize(text, unigrams=false)
    if(tokens.isEmpty()) return "0".repeat(16)
    val counts = tokens.groupingBy{it}.eachCount()
    val bits = IntArray(64)
    for((tok,cnt) in counts){
        val hash = md5(tok); val weight = (Math.log((cnt+1).toDouble())*10).toInt().coerceAtLeast(1)
        for(i in 0 until 64){ val bit = (hash[i/8].toInt() shr (7 - i%8)) and 1; bits[i] += if(bit==1) weight else -weight }
    }
    var out=0L; for(i in 0 until 64) if(bits[i]>0) out = out or (1L shl (63-i))
    return "%016x".format(out)
}

private fun md5(s:String): ByteArray = MessageDigest.getInstance("MD5").digest(s.toByteArray())
fun hammingDistance(a:String,b:String): Int {
    if(a.length!=16||b.length!=16) return 64
    var d=0; for(i in 0 until 16){ val xor = a.substring(i*2,i*2+2).toInt(16) xor b.substring(i*2,i*2+2).toInt(16); d+= Integer.bitCount(xor) }
    // Actually each hex pair is 1 byte, need 8 bytes = 16 hex chars
    // Above loop overcounts; correct:
    var dist=0; for(i in 0 until 8){ val ca = a.substring(i*2,i*2+2).toInt(16); val cb=b.substring(i*2,i*2+2).toInt(16); dist+=Integer.bitCount(ca xor cb) }
    return dist
}

fun slugify(s:String)= s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifBlank{"section"}

fun chunkMarkdown(md:String, targetTokens:Int=420, maxTokens:Int=900, minTokens:Int=60, overlapTokens:Int=40): List<Chunk> {
    val lines = md.lines()
    data class Block(val type:String,val text:String)
    val blocks = mutableListOf<Block>()
    var i=0
    while(i<lines.size){
        val l=lines[i]
        when {
            l.startsWith("```") -> { val sb=StringBuilder(l+"\n"); i++; while(i<lines.size){ sb.append(lines[i]+"\n"); if(lines[i].startsWith("```")){i++;break}; i++ }; blocks.add(Block("code", sb.toString().trimEnd())) }
            l.trim().startsWith("|") && l.contains("|") -> { val sb=StringBuilder(); while(i<lines.size && lines[i].contains("|")){ sb.append(lines[i]+"\n"); i++ }; blocks.add(Block("table", sb.toString().trimEnd())) }
            l.trim().startsWith("#") -> { blocks.add(Block("heading", l)); i++ }
            l.isBlank() -> i++
            else -> { val sb=StringBuilder(); while(i<lines.size && lines[i].isNotBlank() && !lines[i].trim().startsWith("#") && !lines[i].startsWith("```")){ sb.append(lines[i]+"\n"); i++ }; val para=sb.toString().trim(); if(para.isNotEmpty()){ if(estimateTokens(para)>targetTokens){ splitParagraph(para,targetTokens).forEach{ blocks.add(Block("paragraph", it)) } } else blocks.add(Block("paragraph", para)) } }
        }
    }
    val headingStack = mutableListOf<Pair<Int,String>>()
    val chunks = mutableListOf<Chunk>()
    val buf = mutableListOf<Block>(); var bufTokens=0; var charPos=0; var ordinal=0
    var currentHeading=""
    fun headingPath() = headingStack.joinToString(" › ") { it.second }
    fun flush() {
        if(buf.isEmpty()) return
        val content = buf.joinToString("\n\n"){it.text}
        val tokens = estimateTokens(content)
        if(tokens < minTokens && content.lines().all{ it.trim().startsWith("#")}) { buf.clear(); bufTokens=0; return }
        val anchor = slugify(currentHeading.ifBlank{ "chunk-$ordinal" })
        chunks.add(Chunk(ordinal, anchor, headingPath(), content, tokens, charPos, charPos+content.length, simhash(content)))
        charPos+=content.length+2; ordinal++
        // overlap
        if(overlapTokens>0 && buf.isNotEmpty()){
            val tail = content.split("\n\n").takeLast(1).joinToString("\n\n")
            if(estimateTokens(tail) <= overlapTokens){ buf.clear(); buf.add(Block("paragraph", tail)); bufTokens=estimateTokens(tail) } else { buf.clear(); bufTokens=0 }
        } else { buf.clear(); bufTokens=0 }
    }
    for(b in blocks){
        if(b.type=="heading"){
            val level = b.text.takeWhile{it=='#'}.length; val title=b.text.drop(level).trim()
            while(headingStack.isNotEmpty() && headingStack.last().first >= level) headingStack.removeAt(headingStack.size-1)
            headingStack.add(level to title); currentHeading=title
            if(bufTokens>=minTokens) flush()
            buf.add(b); bufTokens+=estimateTokens(b.text)
        } else {
            buf.add(b); bufTokens+=estimateTokens(b.text)
            if(bufTokens>=targetTokens) flush()
            if(bufTokens>=maxTokens) flush()
        }
    }
    if(buf.isNotEmpty()) flush()
    return chunks.filter{ !(it.content.trim().lines().all{ l-> l.trim().startsWith("#") }) }
}

private fun splitParagraph(para:String, target:Int): List<String> {
    val sentences = para.split(Regex("(?<=[.!?。！？;；\\n])\\s+"))
    if(sentences.size<=1){
        val out=mutableListOf<String>(); var cur=""
        for(ch in para){ cur+=ch; if(estimateTokens(cur)>=target){ out.add(cur); cur="" } }
        if(cur.isNotEmpty()) out.add(cur); return out
    }
    val out=mutableListOf<String>(); var cur=""
    for(s in sentences){ if(estimateTokens(cur+" "+s) > target && cur.isNotEmpty()){ out.add(cur.trim()); cur=s } else cur = if(cur.isEmpty()) s else "$cur $s" }
    if(cur.isNotEmpty()) out.add(cur.trim()); return out
}
