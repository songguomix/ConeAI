package com.cone.agent.data.remote

import com.cone.agent.search.SearchEngine
import com.cone.agent.search.SearchParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class SearchResult(val title: String, val url: String, val snippet: String)

@Singleton
class WebSearchClient @Inject constructor(private val client: OkHttpClient, private val engine: dagger.Lazy<SearchEngine>) {
    suspend fun search(query: String, maxResults: Int = 5): Result<List<SearchResult>> = runCatching {
        val hybrid = searchHybrid(query, maxResults, 4000)
        hybrid.results.map { SearchResult(it.title, it.url, it.snippet) }
    }

    suspend fun searchHybrid(query: String, maxResults:Int=8, maxTokens:Int=4000): com.cone.agent.search.SearchResponse {
        val bing = runCatching { withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder().url("https://www.bing.com/search?q=$encoded&setlang=en").header("User-Agent", USER_AGENT).header("Accept-Language", "en-US,en;q=0.9").build()
            val html = client.newCall(request).execute().use { resp -> if (!resp.isSuccessful) error("Bing HTTP ${resp.code}"); resp.body?.string().orEmpty() }
            parse(html, maxResults)
        } }.getOrNull().orEmpty()
        if (bing.isNotEmpty()) {
            val items = bing.map { com.cone.agent.search.SearchResultItem(it.title, it.url, it.snippet, it.snippet, 0.0, "", "") }
            val ctx = items.mapIndexed { i,r -> "[${i+1}] ${r.title}\n${r.snippet}\n${r.url}" }.joinToString("\n\n")
            runCatching { bing.forEach { engine.get().indexUrl(it.url) } }
            return com.cone.agent.search.SearchResponse(query, items, ctx, 0, false)
        }
        val local = runCatching { engine.get().search(SearchParams(query,maxResults,maxTokens)) }.getOrNull()
        if (local != null && local.results.isNotEmpty()) return local
        return local ?: com.cone.agent.search.SearchResponse(query, emptyList(), "", 0, false)
    }
    suspend fun fetch(url:String, maxTokens:Int=4000) = engine.get().fetch(url, maxTokens=maxTokens)
    suspend fun index(url:String, html:String?=null) = engine.get().indexUrl(url, html)

    private fun parse(html: String, max: Int): List<SearchResult> {
        val titles = TITLE_RE.findAll(html).toList()
        val snippets = SNIPPET_RE.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return titles.mapIndexedNotNull { i, m ->
            val url = m.groupValues[1]; val title = clean(m.groupValues[2])
            if (title.isBlank() || !url.startsWith("http") || url.contains("bing.com")) null else SearchResult(title, url, snippets.getOrNull(i).orEmpty())
        }.take(max)
    }
    private fun clean(s: String): String = s.replace(TAG_RE, "").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&#x27;","'").replace("&nbsp;"," ").trim()
    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        val TITLE_RE = Regex("""<h2>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val SNIPPET_RE = Regex("""<p class="b_[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val TAG_RE = Regex("<[^>]+>")
    }
}
