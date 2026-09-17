package com.darkmessage.app.data.repository

import com.darkmessage.app.data.model.Chat
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getAllChats(): Flow<List<Chat>>
    suspend fun getChatById(id: Long): Chat?
    suspend fun addChat(name: String, passphrase: String): Long
    suspend fun updateChat(chat: Chat, newPassphrase: String? = null)
    suspend fun deleteChat(chat: Chat)
    suspend fun getPassphrase(chatId: Long): String?
    suspend fun updateLastActivity(chatId: Long)
}
