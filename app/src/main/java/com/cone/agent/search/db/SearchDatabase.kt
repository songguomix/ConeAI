package com.cone.agent.search.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SearchDocumentEntity::class, SearchChunkEntity::class, SearchChunkFts::class], version = 1, exportSchema = false)
abstract class SearchDatabase : RoomDatabase() {
    abstract fun searchDao(): SearchDao
}
