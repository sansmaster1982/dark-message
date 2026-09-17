package com.darkmessage.app.data.security

interface SecureStorage {
    suspend fun storePassphrase(chatId: Long, passphrase: String)
    suspend fun getPassphrase(chatId: Long): String?
    suspend fun deletePassphrase(chatId: Long)
}
