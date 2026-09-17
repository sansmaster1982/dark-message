package com.darkmessage.app.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.darkmessage.app.data.model.Chat
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllChats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChatById(id: Long): Chat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: Chat): Long

    @Update
    suspend fun updateChat(chat: Chat)

    @Delete
    suspend fun deleteChat(chat: Chat)

    @Query("UPDATE chats SET lastActivityAt = :timestamp WHERE id = :chatId")
    suspend fun updateLastActivity(chatId: Long, timestamp: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM chats")
    suspend fun getNextSortOrder(): Int
}
