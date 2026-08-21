package com.cone.agent.search.index

import com.cone.agent.search.db.SearchChunkEntity
import com.cone.agent.search.db.SearchDao
import com.cone.agent.search.db.SearchDocumentEntity
import com.cone.agent.search.extract.ExtractedPage
import com.cone.agent.search.extract.chunkMarkdown
import com.cone.agent.search.text.toIndexString
import java.net.URL
import java.security.MessageDigest

class SearchIndexer(private val dao: SearchDao) {
    suspend fun indexPage(url: String, page: ExtractedPage, collection:String="default"): Long {
        val normalized = normalizeUrl(url)
        val host = try{ URL(normalized).host.lowercase().removePrefix("www.") }catch(_:Exception){""}
        val hash = sha256(page.markdown).take(32)
        val existing = dao.getDocumentByUrl(normalized)
        if (existing != null && existing.contentHash == hash) {
            dao.updateDocument(existing.id, page.title, page.description, page.markdown, hash, page.wordCount, page.tokenCount, java.time.Instant.now().toString(), page.quality)
            return existing.id
        }
        val docId = if (existing != null) {
            dao.deleteFtsByDocId(existing.id); dao.deleteChunks(existing.id)
            dao.updateDocument(existing.id, page.title, page.description, page.markdown, hash, page.wordCount, page.tokenCount, java.time.Instant.now().toString(), page.quality)
            existing.id
        } else {
            dao.insertDocument(SearchDocumentEntity(url=normalized, host=host, title=page.title, description=page.description, siteName=page.siteName, lang=page.lang, markdown=page.markdown, contentHash=hash, wordCount=page.wordCount, tokenCount=page.tokenCount, publishedAt=page.publishedAt, quality=page.quality, collection=collection))
        }
        val chunks = chunkMarkdown(page.markdown)
        for (c in chunks) {
            val id = dao.insertChunk(SearchChunkEntity(docId=docId, ordinal=c.ordinal, anchor=c.anchor, headingPath=c.headingPath, content=c.content, tokenCount=c.tokenCount, charStart=c.charStart, charEnd=c.charEnd, simhash=c.simhash))
            val tok = toIndexString(c.headingPath+" "+c.content)
            val titleTok = toIndexString(page.title+" "+page.description)
            val urlTok = urlTokens(normalized)
            dao.insertFts(tok, titleTok, urlTok)
        }
        return docId
    }

    suspend fun deleteByUrl(url:String){ val n=normalizeUrl(url); val doc=dao.getDocumentByUrl(n)?:return; dao.deleteFtsByDocId(doc.id); dao.deleteChunks(doc.id); dao.deleteByUrl(n) }

    fun normalizeUrl(raw:String): String = try{
        val u=URL(raw); val host=u.host.lowercase().removePrefix("www."); val port = if(u.port != -1 && u.port!=80 && u.port!=443) ":${u.port}" else ""; val path = u.path.trimEnd('/'); val q = u.query?.split("&")?.filter{ !it.startsWith("utm_") && !it.startsWith("fbclid") }?.sorted()?.joinToString("&")?.let{ if(it.isNotBlank()) "?$it" else "" } ?: ""; "${u.protocol}://$host$port$path$q"
    }catch(_:Exception){ raw.trim() }

    private fun urlTokens(url:String)= try{ val u=URL(url); toIndexString(u.host+" "+u.path.replace("/"," ")) }catch(_:Exception){ toIndexString(url) }
    private fun sha256(s:String)= MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
}
