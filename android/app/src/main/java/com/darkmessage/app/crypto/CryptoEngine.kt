package com.darkmessage.app.crypto

import com.darkmessage.app.data.model.ContentType
import com.darkmessage.app.data.model.DecryptionResult

interface CryptoEngine {
    suspend fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        contentType: ContentType
    ): ByteArray

    suspend fun decrypt(
        payload: ByteArray,
        passphrase: CharArray
    ): DecryptionResult
}
