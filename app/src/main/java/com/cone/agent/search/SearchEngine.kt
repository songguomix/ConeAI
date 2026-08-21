package com.cone.agent.search

import com.cone.agent.search.db.SearchDao
import com.cone.agent.search.extract.extractFromHtml
import com.cone.agent.search.extract.extractFromPlainText
import com.cone.agent.search.index.SearchIndexer
import com.cone.agent.search.text.estimateTokens
import com.cone.agent.search.text.truncateToTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class SearchParams(val query:String, val maxResults:Int=8, val maxTokens:Int=4000, val mode:String="passages", val diversity:Double=0.35)
data class SearchResultItem(val title:String, val url:String, val content:String, val snippet:String, val score:Double, val anchor:String, val headingPath:String)
data class SearchResponse(val query:String, val results:List<SearchResultItem>, val context:String, val tokensUsed:Int, val truncated:Boolean)

@Singleton
class SearchEngine @Inject constructor(private val dao: SearchDao, private val indexer: SearchIndexer, private val client: OkHttpClient) {
    suspend fun search(params: SearchParams): SearchResponse = withContext(Dispatchers.IO) {
        val q = parseQuery(params.query); val match = buildMatchExpression(q) ?: return@withContext SearchResponse(params.query, emptyList(), "", 0, false)
        val pool = minOf(400, maxOf(60, (params.maxResults)*8))
        val rows = try{ dao.searchFts(match, pool) }catch(_:Exception){ emptyList() }
        val candidates = rows.map{ Candidate(it.chunkId,it.docId,it.url,it.title,it.description,it.host,it.headingPath,it.anchor,it.content,it.tokenCount,it.simhash,it.quality,it.publishedAt,it.fetchedAt,it.ordinal,it.bm25) }
        val scored = scoreCandidates(candidates,q)
        val diversified = diversify(scored, params.maxResults, lambda=params.diversity)
        val packed = packToBudget(diversified, params.maxTokens)
        val items = packed.results.map{
            val snippet = bestSnippet(it.content, q.terms, 140)
            SearchResultItem(it.candidate.title, it.candidate.url+"#"+it.candidate.anchor, it.content, snippet, it.candidate.score, it.candidate.anchor, it.candidate.headingPath)
        }
        val ctx = items.mapIndexed{i,r-> "[${i+1}] ${r.title} — ${r.headingPath}\nSource: ${r.url}\n${r.content}"}.joinToString("\n\n")
        SearchResponse(params.query, items, ctx, packed.tokensUsed, packed.resultsTruncated>0)
    }

    suspend fun fetch(url:String, query:String?=null, maxTokens:Int=4000): FetchResult = withContext(Dispatchers.IO) {
        val norm = indexer.normalizeUrl(url)
        dao.getDocumentByUrl(norm)?.let { doc ->
            val chunks = dao.getChunks(doc.id)
            var content = chunks.joinToString("\n\n"){it.content}
            if(!query.isNullOrBlank()){ val q=parseQuery(query); content = chunks.filter{ c-> q.terms.any{ c.content.contains(it, ignoreCase=true)} }.joinToString("\n\n"){it.content}.ifBlank{content} }
            val t = truncateToTokens(content, maxTokens)
            return@withContext FetchResult(url, doc.title, doc.host, t.text, estimateTokens(t.text), t.truncated, "index")
        }
        val html = fetchHtml(url) ?: return@withContext FetchResult(url,"","", "",0,false,"live")
        val page = if(html.trimStart().startsWith("<")) extractFromHtml(html,url) else extractFromPlainText(html,url)
        val t = truncateToTokens(page.markdown, maxTokens)
        FetchResult(url, page.title, page.siteName.ifBlank{ try{ java.net.URL(url).host }catch(_:Exception){""} }, t.text, estimateTokens(t.text), t.truncated, "live")
    }

    suspend fun indexUrl(url:String, html:String? = null): Long {
        val body = html ?: fetchHtml(url) ?: error("fetch failed")
        val page = if(body.trimStart().startsWith("<")) extractFromHtml(body,url) else extractFromPlainText(body,url)
        return indexer.indexPage(url, page)
    }

    private fun fetchHtml(url:String): String? = try{
        val req = Request.Builder().url(url).header("User-Agent","ConeAI/1.0").build()
        client.newCall(req).execute().use{ r-> if(!r.isSuccessful) null else r.body?.string() }
    }catch(_:Exception){ null }
}

data class FetchResult(val url:String,val title:String,val site:String,val content:String,val tokens:Int,val truncated:Boolean,val source:String)
