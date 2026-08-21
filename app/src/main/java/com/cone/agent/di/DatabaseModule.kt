package com.cone.agent.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cone.agent.core.Constants
import com.cone.agent.data.local.ConeDatabase
import com.cone.agent.data.local.dao.ConversationDao
import com.cone.agent.data.local.dao.MessageDao
import com.cone.agent.data.local.dao.ModelDao
import com.cone.agent.data.local.dao.ProviderDao
import com.cone.agent.search.db.SearchDao
import com.cone.agent.search.db.SearchDatabase
import com.cone.agent.search.index.SearchIndexer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** v7 → v8: adds the per-provider "manual model list" flag without wiping existing data. */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE providers ADD COLUMN manualModels INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ConeDatabase =
        Room.databaseBuilder(context, ConeDatabase::class.java, Constants.DB_NAME)
            .addMigrations(MIGRATION_7_8)
            // Keep destructive fallback only as a last resort for unknown version jumps.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideProviderDao(db: ConeDatabase): ProviderDao = db.providerDao()

    @Provides
    fun provideModelDao(db: ConeDatabase): ModelDao = db.modelDao()

    @Provides
    fun provideMessageDao(db: ConeDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: ConeDatabase): ConversationDao = db.conversationDao()

    @Provides
    @Singleton
    fun provideSearchDatabase(@ApplicationContext context: Context): SearchDatabase =
        Room.databaseBuilder(context, SearchDatabase::class.java, "search.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideSearchDao(db: SearchDatabase): SearchDao = db.searchDao()

    @Provides
    @Singleton
    fun provideSearchIndexer(dao: SearchDao): SearchIndexer = SearchIndexer(dao)
}
