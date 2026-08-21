package com.cone.agent.search.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_documents WHERE url = :url LIMIT 1")
    suspend fun getDocumentByUrl(url: String): SearchDocumentEntity?

    @Query("SELECT * FROM search_documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: Long): SearchDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: SearchDocumentEntity): Long

    @Query("UPDATE search_documents SET title=:title, description=:desc, markdown=:md, contentHash=:hash, wordCount=:wc, tokenCount=:tc, fetchedAt=:fetched, quality=:q WHERE id=:id")
    suspend fun updateDocument(id: Long, title: String, desc: String, md: String, hash: String, wc: Int, tc: Int, fetched: String, q: Double)

    @Query("DELETE FROM search_documents WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("SELECT * FROM search_chunks WHERE docId = :docId ORDER BY ordinal")
    suspend fun getChunks(docId: Long): List<SearchChunkEntity>

    @Insert
    suspend fun insertChunk(chunk: SearchChunkEntity): Long

    @Query("DELETE FROM search_chunks WHERE docId = :docId")
    suspend fun deleteChunks(docId: Long)

    @Query("DELETE FROM search_chunks_fts WHERE rowid IN (SELECT id FROM search_chunks WHERE docId = :docId)")
    suspend fun deleteFtsByDocId(docId: Long)

    @Query("INSERT INTO search_chunks_fts(tok, titleTok, urlTok) VALUES (:tok, :titleTok, :urlTok)")
    suspend fun insertFts(tok: String, titleTok: String, urlTok: String)

    @Query("SELECT c.id as chunkId, c.docId, d.url, d.title, d.description, d.host, c.headingPath, c.anchor, c.content, c.tokenCount, c.simhash, d.quality, d.publishedAt, d.fetchedAt, c.ordinal, 0 as bm25 FROM search_chunks c JOIN search_documents d ON d.id=c.docId JOIN search_chunks_fts ON search_chunks_fts.rowid=c.id WHERE search_chunks_fts MATCH :match LIMIT :limit")
    suspend fun searchFts(match: String, limit: Int): List<FtsRow>

    @Query("SELECT COUNT(*) FROM search_documents")
    suspend fun countDocs(): Int

    @Query("SELECT COUNT(*) FROM search_chunks")
    suspend fun countChunks(): Int
}

data class FtsRow(val chunkId: Long, val docId: Long, val url: String, val title: String, val description: String, val host: String, val headingPath: String, val anchor: String, val content: String, val tokenCount: Int, val simhash: String, val quality: Double, val publishedAt: String?, val fetchedAt: String, val ordinal: Int, val bm25: Double)
