package com.darkmessage.app.crypto

import com.darkmessage.app.core.constants.CryptoConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

class Pbkdf2KeyDeriver @Inject constructor() : KeyDeriver {

    override suspend fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray
    ): ByteArray = withContext(Dispatchers.Default) {
        // Convert passphrase to UTF-8 bytes manually for cross-platform compatibility with iOS
        // iOS uses Array(passphrase.utf8) which produces UTF-8 bytes
        // Java's PBEKeySpec converts chars differently, causing mismatch with non-ASCII passwords
        val passphraseBytes = String(passphrase).toByteArray(Charsets.UTF_8)
        try {
            pbkdf2(
                passphraseBytes,
                salt,
                CryptoConstants.PBKDF2_ITERATIONS,
                CryptoConstants.PBKDF2_KEY_LENGTH / 8  // bits to bytes
            )
        } finally {
            passphraseBytes.fill(0)
        }
    }

    /**
     * Manual PBKDF2-HMAC-SHA512 implementation using UTF-8 password bytes.
     * This ensures identical key derivation with iOS CCKeyDerivationPBKDF.
     */
    private fun pbkdf2(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        // RFC 2104: an HMAC key shorter than the block size (128 bytes for SHA-512) is
        // zero-padded, so an EMPTY password is equivalent to 128 zero bytes. JDK's
        // SecretKeySpec rejects a 0-byte key, hence the explicit substitution; the
        // result is identical to iOS CCKeyDerivationPBKDF with passwordLen == 0.
        val hmacKey = if (password.isEmpty()) ByteArray(128) else password
        mac.init(SecretKeySpec(hmacKey, "HmacSHA512"))

        val hashLength = 64  // SHA-512 output = 64 bytes
        val blocks = (keyLength + hashLength - 1) / hashLength
        val result = ByteArray(keyLength)

        for (blockIndex in 1..blocks) {
            // U1 = PRF(password, salt || INT_32_BE(blockIndex))
            mac.reset()
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (blockIndex shr 24 and 0xFF).toByte(),
                    (blockIndex shr 16 and 0xFF).toByte(),
                    (blockIndex shr 8 and 0xFF).toByte(),
                    (blockIndex and 0xFF).toByte()
                )
            )
            var u = mac.doFinal()
            val block = u.copyOf()

            // U2..Uc
            for (i in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (j in block.indices) {
                    block[j] = (block[j].toInt() xor u[j].toInt()).toByte()
                }
            }

            // Copy to result
            val offset = (blockIndex - 1) * hashLength
            val len = minOf(hashLength, keyLength - offset)
            System.arraycopy(block, 0, result, offset, len)
        }

        return result
    }

    override fun generateSalt(): ByteArray {
        val salt = ByteArray(CryptoConstants.SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }
}
