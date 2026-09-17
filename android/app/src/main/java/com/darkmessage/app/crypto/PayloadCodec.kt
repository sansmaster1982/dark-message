package com.darkmessage.app.crypto

import com.darkmessage.app.core.constants.CryptoConstants
import com.darkmessage.app.data.model.ContentType
import com.darkmessage.app.data.model.MessagePayload
import com.darkmessage.app.data.model.PayloadVersion
import java.nio.ByteBuffer

object PayloadCodec {

    /**
     * Binary layout:
     * [version: 1B][contentType: 1B][salt: 16B][nonce: 12B][ciphertext: NB]
     *
     * Total header = 30 bytes, followed by variable-length ciphertext
     * (ciphertext includes the 16-byte GCM auth tag appended by javax.crypto)
     */
    fun encode(payload: MessagePayload): ByteArray {
        val totalSize = CryptoConstants.VERSION_LENGTH +
                CryptoConstants.CONTENT_TYPE_LENGTH +
                CryptoConstants.SALT_LENGTH +
                CryptoConstants.GCM_NONCE_LENGTH +
                payload.ciphertext.size

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(payload.version.code)
        buffer.put(payload.contentType.code)
        buffer.put(payload.salt)
        buffer.put(payload.nonce)
        buffer.put(payload.ciphertext)
        return buffer.array()
    }

    /**
     * Bytes some transports put in front of an attachment. A valid payload always
     * starts with the version byte 0x01, so none of these can ever be the real first
     * byte and skipping them cannot damage a good file. Nothing is stripped from the
     * END: the last byte belongs to the GCM tag and may legally be 0x0A.
     *
     * Observed on 2026-09-13: every .darkm file that travelled from an iPhone to an
     * Android phone arrived with a leading CR LF, and every one of them was rejected
     * as "invalid data format" although the payload behind those two bytes was intact.
     */
    private fun withoutLeadingNoise(data: ByteArray): ByteArray {
        var start = 0
        while (start < data.size) {
            // A UTF-8 byte order mark, which anything that treats the attachment as
            // text is liable to prepend. 0xEF can never be a version byte either.
            if (start + 2 < data.size &&
                (data[start].toInt() and 0xFF) == 0xEF &&
                (data[start + 1].toInt() and 0xFF) == 0xBB &&
                (data[start + 2].toInt() and 0xFF) == 0xBF
            ) {
                start += 3
                continue
            }
            if (isAsciiWhitespace(data[start].toInt() and 0xFF)) start++ else break
        }
        return if (start == 0) data else data.copyOfRange(start, data.size)
    }

    fun decode(raw: ByteArray): MessagePayload? {
        val data = withoutLeadingNoise(raw)
        if (data.size < CryptoConstants.MIN_PAYLOAD_SIZE) return null

        val buffer = ByteBuffer.wrap(data)

        val version = PayloadVersion.fromCode(buffer.get()) ?: return null
        val contentType = ContentType.fromCode(buffer.get()) ?: return null

        val salt = ByteArray(CryptoConstants.SALT_LENGTH)
        buffer.get(salt)

        val nonce = ByteArray(CryptoConstants.GCM_NONCE_LENGTH)
        buffer.get(nonce)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        return MessagePayload(version, contentType, salt, nonce, ciphertext)
    }

    /**
     * Normalises base64 that travelled as TEXT through a messenger or a mail client.
     * Removes every whitespace character (clients wrap long lines), accepts the
     * URL-safe alphabet as well as the standard one, and restores missing padding.
     * Returns null when what is left is not base64 at all.
     *
     * Deliberately NOT a "keep only base64 characters" filter: a signature line such
     * as "Sent from my iPhone" is made of letters that all belong to the alphabet, so
     * such a filter would silently splice it into the message and produce garbage.
     */
    fun normalizeBase64(raw: String): String? {
        val compact = buildString(raw.length) {
            for (ch in raw) {
                // Explicit ASCII set, NOT Char.isWhitespace(): Kotlin and Swift disagree
                // on which code points count (U+0085 is whitespace for Swift but not for
                // Kotlin, U+001C..U+001F the other way round). The same mismatch once
                // renamed a chat across platforms, see CLAUDE.md.
                if (isAsciiWhitespace(ch)) continue
                when (ch) {
                    '-' -> append('+')
                    '_' -> append('/')
                    else -> append(ch)
                }
            }
        }
        if (compact.isEmpty()) return null
        for (ch in compact) {
            val ok = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '+' || ch == '/' || ch == '='
            if (!ok) return null
        }
        val withoutPadding = compact.trimEnd('=')
        val remainder = withoutPadding.length % 4
        if (remainder == 1) return null
        val padding = if (remainder == 0) "" else "=".repeat(4 - remainder)
        return withoutPadding + padding
    }

    /**
     * The six ASCII whitespace characters, written as code points so no editor or
     * line-ending setting can change them. Deliberately NOT Char.isWhitespace():
     * Kotlin and Swift disagree on which code points count, and the same kind of
     * mismatch once renamed a chat across the two platforms (see CLAUDE.md).
     */
    private fun isAsciiWhitespace(ch: Char): Boolean = isAsciiWhitespace(ch.code)

    /**
     * The six ASCII whitespace bytes. One set is used everywhere transport damage is
     * tolerated: in front of a payload, at the end of one, and inside pasted base64.
     * iOS uses exactly these six in the same three places.
     */
    fun isAsciiWhitespace(code: Int): Boolean =
        code == 0x09 || code == 0x0A || code == 0x0B ||
                code == 0x0C || code == 0x0D || code == 0x20

    /**
     * Last resort for a file whose bytes are not a payload at all but the base64
     * TEXT of one. A transport that treats the attachment as text can hand over
     * exactly that, and the result is indistinguishable from a damaged file for the
     * person holding the phone: everything else about the message is intact.
     *
     * Only attempted after the binary parse has already failed, and only when the
     * bytes really are printable base64, so a genuinely damaged file still fails.
     */
    fun decodeFromBase64Text(data: ByteArray): MessagePayload? {
        if (data.size < MIN_BASE64_TEXT_SIZE || data.size > MAX_BASE64_TEXT_SIZE) return null
        val text = try {
            String(data, Charsets.US_ASCII)
        } catch (e: Exception) {
            return null
        }
        val normalized = normalizeBase64(text) ?: return null
        // java.util.Base64, not android.util.Base64: this class has to stay free of
        // Android APIs so it can be unit-tested on the JVM, and minSdk 26 has it.
        val decoded = try {
            java.util.Base64.getDecoder().decode(normalized)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return decode(decoded)
    }

    private const val MIN_BASE64_TEXT_SIZE = 60          // 46 payload bytes in base64
    private const val MAX_BASE64_TEXT_SIZE = 64 * 1024 * 1024
}
