package com.cone.agent.search.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "search_documents")
data class SearchDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val canonicalUrl: String? = null,
    val host: String,
    val title: String,
    val description: String = "",
    val siteName: String = "",
    val lang: String? = null,
    val markdown: String = "",
    val contentHash: String = "",
    val wordCount: Int = 0,
    val tokenCount: Int = 0,
    val publishedAt: String? = null,
    val fetchedAt: String = java.time.Instant.now().toString(),
    val httpStatus: Int = 200,
    val quality: Double = 0.5,
    val collection: String = "default",
    val tags: String = "[]",
    val kind: String = "page",
    val langCode: String = "",
    val repo: String = ""
)

@Entity(tableName = "search_chunks", foreignKeys = [ForeignKey(entity = SearchDocumentEntity::class, parentColumns = ["id"], childColumns = ["docId"], onDelete = ForeignKey.CASCADE)], indices = [Index("docId"), Index("simhash")])
data class SearchChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val ordinal: Int,
    val anchor: String,
    val headingPath: String,
    val content: String,
    val tokenCount: Int,
    val charStart: Int,
    val charEnd: Int,
    val simhash: String,
    val symbol: String = ""
)

@Fts4
@Entity(tableName = "search_chunks_fts")
data class SearchChunkFts(
    val tok: String,
    val titleTok: String,
    val urlTok: String
)
