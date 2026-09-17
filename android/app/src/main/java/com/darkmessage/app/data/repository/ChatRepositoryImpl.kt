package com.darkmessage.app.data.repository

import com.darkmessage.app.data.database.ChatDao
import com.darkmessage.app.data.model.Chat
import com.darkmessage.app.data.security.SecureStorage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao,
    private val secureStorage: SecureStorage
) : ChatRepository {

    override fun getAllChats(): Flow<List<Chat>> = chatDao.getAllChats()

    override suspend fun getChatById(id: Long): Chat? = chatDao.getChatById(id)

    override suspend fun addChat(name: String, passphrase: String): Long {
        val sortOrder = chatDao.getNextSortOrder()
        val chat = Chat(
            name = name,
            colorHue = Random.nextFloat() * 360f,
            sortOrder = sortOrder
        )
        val chatId = chatDao.insertChat(chat)
        try {
            secureStorage.storePassphrase(chatId, passphrase)
        } catch (e: Exception) {
            // A chat whose key was not stored can never decrypt anything, and it would sit
            // in the list looking ordinary while every message sent to it answered "no
            // passphrase for this chat". Take the row back out and let the caller report it.
            chatDao.deleteChat(chat.copy(id = chatId))
            throw e
        }
        return chatId
    }

    override suspend fun updateChat(chat: Chat, newPassphrase: String?) {
        chatDao.updateChat(chat)
        if (newPassphrase != null) {
            secureStorage.storePassphrase(chat.id, newPassphrase)
        }
    }

    override suspend fun deleteChat(chat: Chat) {
        secureStorage.deletePassphrase(chat.id)
        chatDao.deleteChat(chat)
    }

    override suspend fun getPassphrase(chatId: Long): String? {
        return secureStorage.getPassphrase(chatId)
    }

    override suspend fun updateLastActivity(chatId: Long) {
        chatDao.updateLastActivity(chatId, System.currentTimeMillis())
    }
}
