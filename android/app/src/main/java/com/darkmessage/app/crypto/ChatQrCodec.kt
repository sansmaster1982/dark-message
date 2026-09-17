package com.darkmessage.app.crypto

import com.darkmessage.app.core.constants.CryptoConstants
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A parsed, still encrypted QR body (payload format v1).
 *
 * Decoded body layout: kdfId(1 B) | salt(16 B) | nonce(12 B) | ciphertext||GCM_tag(N B)
 */
class ParsedQr(
    val kdfId: Byte,
    val salt: ByteArray,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedQr) return false
        return kdfId == other.kdfId &&
            salt.contentEquals(other.salt) &&
            nonce.contentEquals(other.nonce) &&
            ciphertextAndTag.contentEquals(other.ciphertextAndTag)
    }

    override fun hashCode(): Int {
        var result = kdfId.toInt()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertextAndTag.contentHashCode()
        return result
    }

    /** Never renders the payload itself. */
    override fun toString(): String = "ParsedQr(kdfId=$kdfId, ctTag=${ciphertextAndTag.size}B)"
}

/** Result of [ChatQrCodec.parse]. No crypto has run yet. */
sealed interface QrParseResult {
    data class Ok(val parsed: ParsedQr) : QrParseResult

    /** Not a Dark Message QR text at all (wrong scheme/host). */
    data object NotDarkMessage : QrParseResult

    /** Right prefix, but a version or kdfId this build does not know. */
    data object UnsupportedVersion : QrParseResult

    /** Right prefix and version, but the payload is damaged or out of bounds. */
    data object Malformed : QrParseResult
}

/** Result of [ChatQrCodec.open]. */
sealed interface QrOpenResult {
    data class Ok(val name: String?, val passphrase: String) : QrOpenResult

    /** GCM authentication failed - reported only after a full PBKDF2 run. */
    data object WrongPin : QrOpenResult

    /** Authenticated, but the plaintext does not follow the v1 grammar. */
    data object Malformed : QrOpenResult
}

/** Reasons [ChatQrCodec.encode] can refuse to build a code. */
enum class QrEncodeError {
    NO_PASSPHRASE,
    PASSPHRASE_TOO_LONG,
    NAME_TOO_LONG,
    INVALID_PIN
}

/** Thrown by [ChatQrCodec.encode]; carries a UI-mappable [error]. */
class ChatQrEncodeException(val error: QrEncodeError) : IllegalArgumentException(error.name)

/**
 * QR chat-key exchange codec, payload format v1.
 *
 *     qr-text   = "darkmessage://chat/1/" base64url-nopad(body)
 *     body      = kdfId(0x01) || salt(16) || nonce(12) || ciphertext||tag
 *     AAD       = "darkmessage-qr-v1" || kdfId || salt || nonce
 *     key       = PBKDF2-HMAC-SHA512(pinBytes, salt, 600000, 32)
 *     plaintext = nameLen(1) || name(UTF-8, 0..64) || passphrase(UTF-8, 1..128)
 *
 * The format is a cross-platform contract with the iOS build (QRChatCodec.swift) -
 * every byte here must stay identical on both platforms.
 *
 * Nothing in this class ever logs its input, the PIN, the key or the passphrase.
 */
class ChatQrCodec(
    private val keyDeriver: KeyDeriver = Pbkdf2KeyDeriver()
) {

    private val secureRandom = SecureRandom()

    /**
     * Builds the QR text. [salt] and [nonce] default to CSPRNG values and are only
     * injected by tests / known-answer vectors.
     *
     * @throws ChatQrEncodeException when the inputs violate the format bounds.
     */
    suspend fun encode(
        name: String,
        passphrase: String,
        pin: String,
        salt: ByteArray = keyDeriver.generateSalt(),
        nonce: ByteArray = randomNonce()
    ): String {
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        try {
            // 1. passphrase bounds
            if (passBytes.isEmpty()) {
                throw ChatQrEncodeException(QrEncodeError.NO_PASSPHRASE)
            }
            if (passBytes.size > MAX_PASSPHRASE_BYTES) {
                throw ChatQrEncodeException(QrEncodeError.PASSPHRASE_TOO_LONG)
            }
            // 2. name bounds (the UI already caps input at 64 bytes; this is the guard)
            val nameBytes = asciiTrim(name).toByteArray(Charsets.UTF_8)
            if (nameBytes.size > MAX_NAME_BYTES) {
                throw ChatQrEncodeException(QrEncodeError.NAME_TOO_LONG)
            }
            // 3. PIN
            if (!PIN_REGEX.matches(pin)) {
                throw ChatQrEncodeException(QrEncodeError.INVALID_PIN)
            }
            require(salt.size == CryptoConstants.SALT_LENGTH) {
                "salt must be ${CryptoConstants.SALT_LENGTH} bytes"
            }
            require(nonce.size == CryptoConstants.GCM_NONCE_LENGTH) {
                "nonce must be ${CryptoConstants.GCM_NONCE_LENGTH} bytes"
            }

            val plaintext = ByteArray(1 + nameBytes.size + passBytes.size)
            plaintext[0] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, plaintext, 1, nameBytes.size)
            System.arraycopy(passBytes, 0, plaintext, 1 + nameBytes.size, passBytes.size)

            val pinChars = pin.toCharArray()
            var key: ByteArray? = null
            try {
                // 4. key derivation (PBKDF2-HMAC-SHA512, 600 000 it., off the main thread)
                key = keyDeriver.deriveKey(pinChars, salt)

                // 5. AES-256-GCM, 128-bit tag appended (same convention as .darkm)
                val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH, nonce)
                )
                cipher.updateAAD(aadFor(KDF_ID_PBKDF2_SHA512, salt, nonce))
                val ctTag = cipher.doFinal(plaintext)

                // 6. body + text
                val body = ByteArray(HEADER_BYTES + ctTag.size)
                body[0] = KDF_ID_PBKDF2_SHA512
                System.arraycopy(salt, 0, body, 1, salt.size)
                System.arraycopy(nonce, 0, body, 1 + salt.size, nonce.size)
                System.arraycopy(ctTag, 0, body, HEADER_BYTES, ctTag.size)

                return URI_PREFIX + VERSION + "/" + base64UrlEncode(body)
            } finally {
                // 7. memory hygiene
                key?.fill(0)
                plaintext.fill(0)
                pinChars.fill('\u0000')
            }
        } finally {
            passBytes.fill(0)
        }
    }

    /**
     * Phase 1 of decoding: pure syntax, no crypto (the PIN is unknown at scan time).
     * Never throws, whatever arbitrary text a scanner hands in.
     */
    fun parse(text: String): QrParseResult = try {
        parseInternal(text)
    } catch (e: Exception) {
        QrParseResult.Malformed
    }

    private fun parseInternal(text: String): QrParseResult {
        // P1 - strip leading/trailing ASCII whitespace only (some scanners append a newline)
        val trimmed = asciiTrim(text)

        // P2 - hard size ceiling before any base64 or crypto work. A UTF-8 string is
        // never shorter in bytes than in chars, so the cheap char check short-circuits
        // huge inputs before they are re-encoded.
        if (trimmed.length > MAX_TEXT_BYTES) return QrParseResult.Malformed
        if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            return QrParseResult.Malformed
        }

        // P3 - byte-exact, case-sensitive prefix
        if (!trimmed.startsWith(URI_PREFIX)) return QrParseResult.NotDarkMessage

        // P4 - version / body split at the first '/'
        val rest = trimmed.substring(URI_PREFIX.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return QrParseResult.Malformed // no '/', or empty version
        val version = rest.substring(0, slash)
        val bodyStr = rest.substring(slash + 1)
        if (!version.all { it in '0'..'9' }) return QrParseResult.Malformed

        // P5 - only "1" is understood by this build
        if (version != VERSION) return QrParseResult.UnsupportedVersion

        // P6 - base64url alphabet and length envelope
        if (bodyStr.length < MIN_BODY_CHARS || bodyStr.length > MAX_BODY_CHARS) {
            return QrParseResult.Malformed
        }
        if (bodyStr.length % 4 == 1) return QrParseResult.Malformed
        if (!bodyStr.all { isBase64UrlChar(it) }) return QrParseResult.Malformed

        // P7 - decode (P6 already excluded padding, '+', '/', whitespace and non-ASCII)
        val body = try {
            Base64.getUrlDecoder().decode(bodyStr)
        } catch (e: IllegalArgumentException) {
            return QrParseResult.Malformed
        }
        if (body.size < MIN_BODY_BYTES || body.size > MAX_BODY_BYTES) {
            return QrParseResult.Malformed
        }

        // P8 - an unknown KDF is never a fallback
        if (body[0] != KDF_ID_PBKDF2_SHA512) return QrParseResult.UnsupportedVersion

        // P9 - split the header
        val salt = body.copyOfRange(1, 1 + CryptoConstants.SALT_LENGTH)
        val nonce = body.copyOfRange(1 + CryptoConstants.SALT_LENGTH, HEADER_BYTES)
        val ctTag = body.copyOfRange(HEADER_BYTES, body.size)
        if (ctTag.size < MIN_CT_TAG_BYTES) return QrParseResult.Malformed

        return QrParseResult.Ok(ParsedQr(body[0], salt, nonce, ctTag))
    }

    /**
     * Phase 2: derive the key from the PIN and authenticate-decrypt the body.
     * Always runs the full PBKDF2 before reporting [QrOpenResult.WrongPin].
     */
    suspend fun open(parsed: ParsedQr, pin: String): QrOpenResult {
        // O1
        if (!PIN_REGEX.matches(pin)) return QrOpenResult.Malformed
        if (parsed.kdfId != KDF_ID_PBKDF2_SHA512) return QrOpenResult.Malformed
        if (parsed.salt.size != CryptoConstants.SALT_LENGTH) return QrOpenResult.Malformed
        if (parsed.nonce.size != CryptoConstants.GCM_NONCE_LENGTH) return QrOpenResult.Malformed
        if (parsed.ciphertextAndTag.size < MIN_CT_TAG_BYTES) return QrOpenResult.Malformed

        val pinChars = pin.toCharArray()
        var key: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            // O2
            key = keyDeriver.deriveKey(pinChars, parsed.salt)
            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH, parsed.nonce)
            )
            cipher.updateAAD(aadFor(parsed.kdfId, parsed.salt, parsed.nonce))

            // O3
            val pt = try {
                cipher.doFinal(parsed.ciphertextAndTag)
            } catch (e: AEADBadTagException) {
                return QrOpenResult.WrongPin
            } catch (e: GeneralSecurityException) {
                return QrOpenResult.Malformed
            }
            plaintext = pt

            // O4 - plaintext grammar
            if (pt.size < 2) return QrOpenResult.Malformed
            val nameLen = pt[0].toInt() and 0xFF
            if (nameLen > MAX_NAME_BYTES) return QrOpenResult.Malformed
            if (1 + nameLen >= pt.size) return QrOpenResult.Malformed // >= 1 passphrase byte
            val passLen = pt.size - 1 - nameLen
            if (passLen > MAX_PASSPHRASE_BYTES) return QrOpenResult.Malformed

            // O5 - strict UTF-8: malformed input is rejected, never substituted
            val rawName = decodeUtf8Strict(pt, 1, nameLen) ?: return QrOpenResult.Malformed
            val passphrase = decodeUtf8Strict(pt, 1 + nameLen, passLen)
                ?: return QrOpenResult.Malformed

            // O6 - the name is trimmed and must not carry control characters; the
            // passphrase is taken verbatim (no trim, no Unicode normalization)
            // ASCII-only trimming, identical to the iOS side (Kotlin's trim() and Swift's
            // .whitespacesAndNewlines cover different sets, which would rename the chat).
            val name = asciiTrim(rawName)
            if (name.any { it.code <= 0x1F || it.code == 0x7F }) return QrOpenResult.Malformed

            return QrOpenResult.Ok(if (name.isEmpty()) null else name, passphrase)
        } finally {
            // O7
            key?.fill(0)
            plaintext?.fill(0)
            pinChars.fill('\u0000')
        }
    }

    /** Six decimal digits, leading zeros kept, from a CSPRNG. */
    fun generatePin(): String =
        String.format(Locale.US, "%06d", secureRandom.nextInt(PIN_SPACE))

    /** A fresh 12-byte AES-GCM nonce. */
    fun randomNonce(): ByteArray =
        ByteArray(CryptoConstants.GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }

    /**
     * A fresh 16-byte PBKDF2 salt, so a caller that has to keep the salt and nonce of a
     * code session in memory does not need its own [KeyDeriver].
     */
    fun randomSalt(): ByteArray = keyDeriver.generateSalt()

    /**
     * UI-only key fingerprint: uppercase hex of the first 4 bytes of
     * SHA-256(UTF-8 passphrase), rendered as "XXXX XXXX". Not part of the payload.
     */
    fun fingerprint(passphrase: String): String {
        val bytes = passphrase.toByteArray(Charsets.UTF_8)
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val out = StringBuilder(9)
            for (i in 0 until FINGERPRINT_BYTES) {
                val b = digest[i].toInt()
                out.append(HEX_DIGITS[(b shr 4) and 0x0F])
                out.append(HEX_DIGITS[b and 0x0F])
                if (i == 1) out.append(' ')
            }
            return out.toString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun aadFor(kdfId: Byte, salt: ByteArray, nonce: ByteArray): ByteArray {
        val aad = ByteArray(AAD_CONTEXT.size + 1 + salt.size + nonce.size)
        System.arraycopy(AAD_CONTEXT, 0, aad, 0, AAD_CONTEXT.size)
        aad[AAD_CONTEXT.size] = kdfId
        System.arraycopy(salt, 0, aad, AAD_CONTEXT.size + 1, salt.size)
        System.arraycopy(nonce, 0, aad, AAD_CONTEXT.size + 1 + salt.size, nonce.size)
        return aad
    }

    private fun base64UrlEncode(body: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(body)

    private fun isBase64UrlChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    private fun isAsciiSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun asciiTrim(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isAsciiSpace(text[start])) start++
        while (end > start && isAsciiSpace(text[end - 1])) end--
        return text.substring(start, end)
    }

    /**
     * Strict UTF-8 decode of bytes[offset, offset + length). Returns null on malformed
     * or unmappable input - never substitutes U+FFFD the way String(bytes, UTF_8) would.
     */
    private fun decodeUtf8Strict(bytes: ByteArray, offset: Int, length: Int): String? {
        if (length == 0) return ""
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    companion object {
        /** URL scheme registered for the secondary deep-link path. */
        const val SCHEME = "darkmessage"

        /** 19 lowercase ASCII chars, compared byte-exactly. */
        const val URI_PREFIX = "darkmessage://chat/"

        /** The only payload version this build produces and accepts. */
        const val VERSION = "1"

        /** PBKDF2-HMAC-SHA512, 600 000 iterations, 6-digit PIN. */
        const val KDF_ID_PBKDF2_SHA512: Byte = 0x01

        const val MAX_NAME_BYTES = 64
        const val MAX_PASSPHRASE_BYTES = 128
        const val PIN_LENGTH = 6

        private const val PIN_SPACE = 1_000_000

        /** kdfId(1) + salt(16) + nonce(12) */
        private const val HEADER_BYTES =
            1 + CryptoConstants.SALT_LENGTH + CryptoConstants.GCM_NONCE_LENGTH

        /** The name may be empty, but one passphrase byte plus the 16-byte tag may not. */
        private const val MIN_CT_TAG_BYTES = 18

        private const val MIN_BODY_BYTES = HEADER_BYTES + MIN_CT_TAG_BYTES // 47
        private const val MAX_BODY_BYTES =
            HEADER_BYTES + 1 + MAX_NAME_BYTES + MAX_PASSPHRASE_BYTES + 16 // 238
        private const val MIN_BODY_CHARS = 63
        private const val MAX_BODY_CHARS = 318

        /** Whole-text ceiling checked before any decoding work. */
        private const val MAX_TEXT_BYTES = 600

        private const val FINGERPRINT_BYTES = 4

        private val AAD_CONTEXT = "darkmessage-qr-v1".toByteArray(Charsets.US_ASCII)
        private val PIN_REGEX = Regex("^[0-9]{6}$")
        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
    }
}
