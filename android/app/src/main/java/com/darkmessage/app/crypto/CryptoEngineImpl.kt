package com.darkmessage.app.crypto

import com.darkmessage.app.core.constants.CryptoConstants
import com.darkmessage.app.data.model.ContentType
import com.darkmessage.app.data.model.DecryptionResult
import com.darkmessage.app.data.model.MessagePayload
import com.darkmessage.app.data.model.PayloadVersion
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

class CryptoEngineImpl @Inject constructor(
    private val keyDeriver: KeyDeriver
) : CryptoEngine {

    override suspend fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        contentType: ContentType
    ): ByteArray {
        val salt = keyDeriver.generateSalt()
        val derivedKey = keyDeriver.deriveKey(passphrase, salt)

        try {
            val secretKey = SecretKeySpec(derivedKey, "AES")
            val nonce = ByteArray(CryptoConstants.GCM_NONCE_LENGTH)
            SecureRandom().nextBytes(nonce)

            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

            val ciphertext = cipher.doFinal(plaintext)

            return PayloadCodec.encode(
                MessagePayload(
                    version = PayloadVersion.V1,
                    contentType = contentType,
                    salt = salt,
                    nonce = nonce,
                    ciphertext = ciphertext
                )
            )
        } finally {
            derivedKey.fill(0)
        }
    }

    override suspend fun decrypt(
        payload: ByteArray,
        passphrase: CharArray
    ): DecryptionResult {
        val parsed = PayloadCodec.decode(payload)
            ?: PayloadCodec.decodeFromBase64Text(payload)
            ?: return DecryptionResult.Error("INVALID_FORMAT")

        val derivedKey = keyDeriver.deriveKey(passphrase, parsed.salt)

        try {
            val plaintext = openTolerantly(derivedKey, parsed.nonce, parsed.ciphertext)
                ?: return DecryptionResult.Error("BAD_PASSPHRASE")

            return when (parsed.contentType) {
                ContentType.TEXT -> DecryptionResult.TextMessage(
                    plaintext.toString(Charsets.UTF_8)
                )
                ContentType.IMAGE -> DecryptionResult.ImageMessage(plaintext)
                ContentType.DOCUMENT -> {
                    // Extract filename: [nameLen:2B][nameUTF8][docBytes].
                    //
                    // Two decisions here have to match iOS DecryptionResult exactly, or the same
                    // payload becomes two different files. A length that does not FIT means these
                    // bytes are not a name frame at all, and the whole plaintext is the file. A
                    // length of ZERO is a well-formed frame from a sender that had no name for the
                    // file, so the two length bytes still come off - keeping them used to hand the
                    // user a document with two junk bytes glued to the front of it.
                    if (plaintext.size >= 2) {
                        val nameLen = ((plaintext[0].toInt() and 0xFF) shl 8) or
                                (plaintext[1].toInt() and 0xFF)
                        if (plaintext.size >= 2 + nameLen) {
                            val docBytes = plaintext.copyOfRange(2 + nameLen, plaintext.size)
                            if (nameLen > 0) {
                                DecryptionResult.DocumentMessage(
                                    docBytes,
                                    String(plaintext, 2, nameLen, Charsets.UTF_8)
                                )
                            } else {
                                DecryptionResult.DocumentMessage(docBytes)
                            }
                        } else {
                            DecryptionResult.DocumentMessage(plaintext)
                        }
                    } else {
                        DecryptionResult.DocumentMessage(plaintext)
                    }
                }
            }
        } catch (e: Exception) {
            return DecryptionResult.Error("DECRYPT_ERROR:${e.message}")
        } finally {
            derivedKey.fill(0)
        }
    }

    /**
     * Opens the sealed box, tolerating a few whitespace bytes appended by whatever
     * carried the file. Mail and messengers have been seen to add both a leading and
     * a trailing CR LF to an attachment; the leading one is handled while parsing,
     * but a trailing one lands inside the GCM tag and makes an intact message look
     * like a wrong passphrase.
     *
     * Only whitespace is ever dropped, at most [MAX_TRAILING_NOISE] bytes, and the
     * result still has to pass GCM authentication - so a successful decrypt is proof
     * that the dropped bytes were not part of the message. The key is derived once by
     * the caller, so the extra attempts cost microseconds, not another PBKDF2 run.
     */
    private fun openTolerantly(
        derivedKey: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray
    ): ByteArray? {
        val secretKey = SecretKeySpec(derivedKey, "AES")
        var candidate = ciphertext
        var dropped = 0
        while (true) {
            try {
                val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH, nonce)
                )
                return cipher.doFinal(candidate)
            } catch (e: AEADBadTagException) {
                if (dropped >= MAX_TRAILING_NOISE || candidate.isEmpty()) return null
                val last = candidate[candidate.size - 1].toInt() and 0xFF
                if (!PayloadCodec.isAsciiWhitespace(last)) return null
                candidate = candidate.copyOfRange(0, candidate.size - 1)
                dropped++
            }
        }
    }

    private companion object {
        /** CR LF is two bytes; four leaves room for a doubled newline. */
        const val MAX_TRAILING_NOISE = 4
    }
}
