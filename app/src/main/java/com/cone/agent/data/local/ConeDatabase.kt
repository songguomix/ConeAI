package com.cone.agent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cone.agent.data.local.dao.ConversationDao
import com.cone.agent.data.local.dao.MessageDao
import com.cone.agent.data.local.dao.ModelDao
import com.cone.agent.data.local.dao.ProviderDao
import com.cone.agent.data.local.entity.ConversationEntity
import com.cone.agent.data.local.entity.MessageEntity
import com.cone.agent.data.local.entity.ModelEntity
import com.cone.agent.data.local.entity.ProviderEntity

@Database(
    entities = [
        ProviderEntity::class,
        ModelEntity::class,
        MessageEntity::class,
        ConversationEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class ConeDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
}
