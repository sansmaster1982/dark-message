package com.darkmessage.app.crypto

interface KeyDeriver {
    suspend fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray
    fun generateSalt(): ByteArray
}
