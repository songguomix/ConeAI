package com.cone.agent.search.extract

import com.cone.agent.search.text.estimateTokens
import java.net.URL

data class ExtractedPage(val title:String, val description:String, val siteName:String, val lang:String?, val canonicalUrl:String?, val publishedAt:String?, val markdown:String, val text:String, val wordCount:Int, val tokenCount:Int, val links:List<String>, val quality:Double)

fun extractFromHtml(html:String, url:String): ExtractedPage {
    val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let{ clean(it) }?.takeIf{it.isNotBlank()}
        ?: Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let{ clean(it) } ?: ""
    val desc = Regex("""<meta[^>]+name=["']description["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let{ clean(it) }
        ?: Regex("""<meta[^>]+property=["']og:description["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let{ clean(it) } ?: ""
    val siteName = Regex("""<meta[^>]+property=["']og:site_name["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let{ clean(it) } ?: ""
    val lang = Regex("""<html[^>]+lang=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    val canonical = Regex("""<link[^>]+rel=["']canonical["'][^>]*href=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    val published = Regex("""<meta[^>]+(?:property|name)=["'](?:article:published_time|datePublished|publish_date|og:updated_time)["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    val links = Regex("""<a[^>]+href=["']([^"']*)["']""", RegexOption.IGNORE_CASE).findAll(html).map{ it.groupValues[1] }.filter{ it.startsWith("http") }.distinct().take(200).toList()
    val body = html.replace(Regex("(?is)<(script|style|noscript|nav|footer|aside)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .let{ clean(it) }
    val md = htmlToMarkdown(html)
    val quality = computeQuality(md, title, published)
    return ExtractedPage(title, desc, siteName, lang, canonical, published, md, body, body.split(Regex("\\s+")).size, estimateTokens(md), links, quality)
}

private fun htmlToMarkdown(html:String): String {
    var s = html
    s = s.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), "")
    s = s.replace(Regex("(?is)<h1[^>]*>(.*?)</h1>")) { "# ${clean(it.groupValues[1])}\n\n" }
    s = s.replace(Regex("(?is)<h2[^>]*>(.*?)</h2>")) { "## ${clean(it.groupValues[1])}\n\n" }
    s = s.replace(Regex("(?is)<h3[^>]*>(.*?)</h3>")) { "### ${clean(it.groupValues[1])}\n\n" }
    s = s.replace(Regex("(?is)<pre[^>]*><code[^>]*>(.*?)</code></pre>")) { "\n```\n${clean(it.groupValues[1])}\n```\n" }
    s = s.replace(Regex("(?is)<code[^>]*>(.*?)</code>")) { "`${clean(it.groupValues[1])}`" }
    s = s.replace(Regex("(?is)<a[^>]+href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>")) { "[${clean(it.groupValues[2])}](${it.groupValues[1]})" }
    s = s.replace(Regex("(?is)<li[^>]*>(.*?)</li>")) { "- ${clean(it.groupValues[1])}\n" }
    s = s.replace(Regex("(?is)<p[^>]*>(.*?)</p>")) { "${clean(it.groupValues[1])}\n\n" }
    s = s.replace(Regex("(?is)<br[^>]*>"), "\n")
    s = s.replace(Regex("<[^>]+>"), " ")
    s = s.replace(Regex("[ \\t]+"), " ")
    s = s.replace(Regex("\\n{3,}"), "\n\n")
    return clean(s).trim()
}

private fun clean(s:String)= s.replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&nbsp;"," ").replace(Regex("\\s+")," ").trim()
private fun computeQuality(md:String,title:String,published:String?):Double {
    var q=0.35; val tokens=estimateTokens(md)
    if(tokens>200) q+=0.1; if(tokens>600) q+=0.1
    if(md.contains("##")) q+=0.05; if(md.contains("```")) q+=0.05
    if(title.isNotBlank()) q+=0.05; if(published!=null) q+=0.05
    if(md.split("\n").count{ it.trim().startsWith("[") } > md.lines().size*0.5) q-=0.2
    return q.coerceIn(0.0,1.0)
}

fun extractFromPlainText(text:String, url:String): ExtractedPage {
    val title = text.lineSequence().firstOrNull{ it.trim().startsWith("#") }?.trim()?.removePrefix("#")?.trim() ?: URL(url).path.substringAfterLast("/").ifBlank{"Untitled"}
    val md = text.trim()
    return ExtractedPage(title,"","",null,null,null,md,md,md.split(Regex("\\s+")).size, estimateTokens(md), emptyList(), 0.5)
}
