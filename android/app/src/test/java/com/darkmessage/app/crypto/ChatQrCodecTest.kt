package com.darkmessage.app.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Known-answer tests for the QR chat-key exchange payload format v1.
 *
 * The four vectors and every negative case below are the cross-platform contract with
 * the iOS build: if one of them fails, key exchange between an Android phone and an
 * iPhone is broken. Fix the code, never the vector.
 *
 * Every non-ASCII test string is rebuilt from the UTF-8 hex the spec lists (or from
 * explicit code points), so this file stays pure ASCII and cannot drift with the
 * source encoding.
 */
class ChatQrCodecTest {

    // ---------------------------------------------------------------- infrastructure

    /**
     * The real PBKDF2 (600 000 iterations), memoized per (pin, salt) across the whole
     * class so the suite pays the ~1 s derivation once per distinct vector instead of
     * once per call. The cached key is copied out because the codec wipes what it gets.
     */
    private class CachingKeyDeriver(
        private val delegate: KeyDeriver = Pbkdf2KeyDeriver()
    ) : KeyDeriver {
        private val cache = ConcurrentHashMap<String, ByteArray>()

        override suspend fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
            val cacheKey = String(passphrase) + "|" + salt.contentToString()
            cache[cacheKey]?.let { return it.copyOf() }
            val derived = delegate.deriveKey(passphrase, salt)
            cache[cacheKey] = derived.copyOf()
            return derived
        }

        override fun generateSalt(): ByteArray = delegate.generateSalt()
    }

    /** One row of section 2, with the section 2 intermediate values. */
    private class Vector(
        val label: String,
        val name: String,
        val passphrase: String,
        val pin: String,
        val saltHex: String,
        val nonceHex: String,
        val keyHex: String,
        val plaintextHex: String,
        val bodyBytes: Int,
        val bodyChars: Int,
        val textChars: Int,
        val fingerprint: String,
        val encoded: String
    )

    private val codec = ChatQrCodec(sharedDeriver)

    // ---------------------------------------------------------------- section 2 vectors

    @Test
    fun `vector 1 encodes to the spec string`() = runBlocking {
        assertEncodesToSpec(V1)
    }

    @Test
    fun `vector 2 encodes to the spec string`() = runBlocking {
        assertEncodesToSpec(V2)
    }

    @Test
    fun `vector 3 encodes to the spec string`() = runBlocking {
        assertEncodesToSpec(V3)
    }

    @Test
    fun `vector 4 encodes to the spec string`() = runBlocking {
        assertEncodesToSpec(V4)
    }

    @Test
    fun `vector 1 parses and opens to the spec name and passphrase`() = runBlocking {
        assertOpensToSpec(V1)
    }

    @Test
    fun `vector 2 parses and opens to the spec name and passphrase`() = runBlocking {
        assertOpensToSpec(V2)
    }

    @Test
    fun `vector 3 parses and opens to the spec name and passphrase`() = runBlocking {
        assertOpensToSpec(V3)
    }

    @Test
    fun `vector 4 parses and opens to the spec name and passphrase`() = runBlocking {
        assertOpensToSpec(V4)
    }

    @Test
    fun `vector plaintexts match the spec intermediate values`() {
        for (v in VECTORS) {
            val nameBytes = v.name.toByteArray(Charsets.UTF_8)
            val passBytes = v.passphrase.toByteArray(Charsets.UTF_8)
            val plaintext = byteArrayOf(nameBytes.size.toByte()) + nameBytes + passBytes
            assertEquals(v.label + ": plaintext", v.plaintextHex, toHex(plaintext))
            assertTrue(v.label + ": name <= 64 B", nameBytes.size <= 64)
            assertTrue(v.label + ": passphrase in 1..128 B", passBytes.size in 1..128)
        }
    }

    @Test
    fun `vector keys match the spec intermediate values`() = runBlocking {
        for (v in VECTORS) {
            val key = sharedDeriver.deriveKey(v.pin.toCharArray(), hex(v.saltHex))
            assertEquals(v.label + ": key", v.keyHex, toHex(key))
            assertEquals(v.label + ": key length", 32, key.size)
        }
    }

    @Test
    fun `vector sizes stay inside the spec envelope`() {
        for (v in VECTORS) {
            val body = bodyBytesOf(v.encoded)
            val bodyStr = bodyStringOf(v.encoded)
            assertEquals(v.label + ": body bytes", v.bodyBytes, body.size)
            assertEquals(v.label + ": body chars", v.bodyChars, bodyStr.length)
            assertEquals(v.label + ": text chars", v.textChars, v.encoded.length)
            assertTrue(v.label + ": body bytes in 47..238", body.size in 47..238)
            assertTrue(v.label + ": body chars in 63..318", bodyStr.length in 63..318)
            assertTrue(v.label + ": body chars not 1 mod 4", bodyStr.length % 4 != 1)
            assertFalse(v.label + ": no padding", bodyStr.contains("="))
            assertFalse(v.label + ": no plus", bodyStr.contains("+"))
            assertFalse(v.label + ": no slash in the body", bodyStr.contains("/"))
            assertTrue(
                v.label + ": base64url alphabet only",
                bodyStr.all {
                    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
                }
            )
            assertTrue(
                v.label + ": printable ASCII only",
                v.encoded.all { it.code in 0x21..0x7E }
            )
        }
    }

    @Test
    fun `fingerprints match the spec`() {
        for (v in VECTORS) {
            assertEquals(
                v.label + ": fingerprint",
                v.fingerprint,
                codec.fingerprint(v.passphrase)
            )
        }
    }

    @Test
    fun `fingerprint format is uppercase hex in two groups of four`() {
        // SHA-256 of the empty string starts with e3 b0 c4 42.
        assertEquals("E3B0 C442", codec.fingerprint(""))
        assertTrue(Regex("^[0-9A-F]{4} [0-9A-F]{4}$").matches(codec.fingerprint("anything")))
    }

    // ---------------------------------------------------------------- wrong PIN

    @Test
    fun `wrong pin on vector 1 reports wrongPin`() = runBlocking {
        assertEquals(QrOpenResult.WrongPin, codec.open(parsedOf(V1.encoded), "123457"))
    }

    @Test
    fun `flipping a ciphertext bit reports wrongPin`() = runBlocking {
        val tampered = withBodyByte(V1.encoded, 40) { (it.toInt() xor 1).toByte() }
        assertEquals(QrOpenResult.WrongPin, codec.open(parsedOf(tampered), V1.pin))
    }

    @Test
    fun `the aad binds the header so a swapped nonce fails authentication`() = runBlocking {
        // Same key (same pin and salt), different nonce: both the AAD and the IV change.
        val tampered = withBodyByte(V1.encoded, 17) { (it.toInt() xor 0xFF).toByte() }
        assertEquals(QrOpenResult.WrongPin, codec.open(parsedOf(tampered), V1.pin))
    }

    // ---------------------------------------------------------- section 1.6 parse negatives

    @Test
    fun `spec 1_6 negative vectors are rejected with the listed kind`() {
        val body = bodyStringOf(V1.encoded)
        val cases = listOf(
            Triple(
                "https url carrying the v1 body",
                "https://darkmessage.app/chat/1/" + body,
                QrParseResult.NotDarkMessage
            ),
            Triple(
                "uppercase scheme",
                "DARKMESSAGE://chat/1/" + body,
                QrParseResult.NotDarkMessage
            ),
            Triple(
                "path version 2",
                "darkmessage://chat/2/" + body,
                QrParseResult.UnsupportedVersion
            ),
            Triple(
                "kdfId 0x02",
                textOf(bodyBytesOf(V1.encoded).also { it[0] = 0x02 }),
                QrParseResult.UnsupportedVersion
            ),
            Triple("padding appended", V1.encoded + "==", QrParseResult.Malformed),
            Triple(
                "plus in the body",
                V1.encoded.replaceRange(PREFIX_1.length + 30, PREFIX_1.length + 31, "+"),
                QrParseResult.Malformed
            ),
            Triple(
                "slash in the body",
                V1.encoded.replaceRange(PREFIX_1.length + 30, PREFIX_1.length + 31, "/"),
                QrParseResult.Malformed
            ),
            Triple(
                "space inside the body",
                V1.encoded.substring(0, PREFIX_1.length + 30) + " " +
                    V1.encoded.substring(PREFIX_1.length + 30),
                QrParseResult.Malformed
            ),
            Triple("truncated to 60 chars", V1.encoded.take(60), QrParseResult.Malformed),
            Triple("body length 1 mod 4", V1.encoded + "AAA", QrParseResult.Malformed),
            Triple("body of 319 chars", PREFIX_1 + "A".repeat(319), QrParseResult.Malformed),
            Triple(
                "non numeric version",
                "darkmessage://chat/x/" + body,
                QrParseResult.Malformed
            ),
            Triple("no version at all", "darkmessage://chat/", QrParseResult.Malformed),
            Triple("version without a slash", "darkmessage://chat/1", QrParseResult.Malformed),
            Triple("empty body", PREFIX_1, QrParseResult.Malformed),
            Triple(
                "empty version",
                "darkmessage://chat//" + body,
                QrParseResult.Malformed
            )
        )
        for ((label, text, expected) in cases) {
            assertEquals(label, expected, codec.parse(text))
        }
    }

    @Test
    fun `the spec 1_6 literals are exactly what this codec would build`() {
        assertEquals(SPEC_PATH_VERSION_2, "darkmessage://chat/2/" + bodyStringOf(V1.encoded))
        assertEquals(SPEC_KDF_ID_2, textOf(bodyBytesOf(V1.encoded).also { it[0] = 0x02 }))
        assertEquals(QrParseResult.UnsupportedVersion, codec.parse(SPEC_PATH_VERSION_2))
        assertEquals(QrParseResult.UnsupportedVersion, codec.parse(SPEC_KDF_ID_2))
    }

    @Test
    fun `a trailing newline is tolerated and the payload still opens`() = runBlocking {
        val opened = codec.open(parsedOf(V1.encoded + "\n"), V1.pin)
        assertEquals(QrOpenResult.Ok(V1.name, V1.passphrase), opened)
    }

    @Test
    fun `only ascii whitespace is stripped`() {
        val expected = QrParseResult.Ok(parsedOf(V1.encoded))
        assertEquals(expected, codec.parse(" \t\r\n" + V1.encoded + "\r\n \t"))
        assertEquals(expected, codec.parse(V1.encoded + "\r\n"))
        // U+00A0 (no-break space) is not ASCII whitespace and must not be trimmed away.
        assertEquals(QrParseResult.Malformed, codec.parse(V1.encoded + codePoint(0x00A0)))
        assertEquals(
            QrParseResult.NotDarkMessage,
            codec.parse(codePoint(0x00A0) + V1.encoded)
        )
    }

    @Test
    fun `parse never throws on arbitrary input`() {
        val junk = listOf(
            "",
            " ",
            "\n\n",
            "hello world",
            "darkmessage:",
            "darkmessage://",
            "darkmessage://chat",
            "darkmessage://chat/1//",
            "darkmessage://chat/1/?x=1",
            "darkmessage://chat/1/#fragment",
            "darkmessage://chat/99999999999999999999999999/" + bodyStringOf(V1.encoded),
            PREFIX_1 + codePoint(0x0416).repeat(120),
            "WIFI:S:net;T:WPA;P:secret;;",
            "A".repeat(10_000),
            PREFIX_1 + "A".repeat(10_000),
            V1.encoded.replace("A", codePoint(0x00E9)),
            V1.encoded.lowercase(),
            V1.encoded.uppercase()
        )
        for (text in junk) {
            assertNotNull("parse must return a result for: " + text.take(40), codec.parse(text))
        }
        // Spot-check the kinds.
        assertEquals(QrParseResult.NotDarkMessage, codec.parse(""))
        assertEquals(QrParseResult.NotDarkMessage, codec.parse("WIFI:S:net;T:WPA;P:secret;;"))
        assertEquals(QrParseResult.NotDarkMessage, codec.parse(V1.encoded.uppercase()))
        assertEquals(QrParseResult.Malformed, codec.parse(PREFIX_1 + "A".repeat(10_000)))
        assertEquals(
            QrParseResult.UnsupportedVersion,
            codec.parse(
                "darkmessage://chat/99999999999999999999999999/" + bodyStringOf(V1.encoded)
            )
        )
    }

    @Test
    fun `a darkm style base64 text is never mistaken for a qr body`() {
        val darkm = Base64.getEncoder().encodeToString(ByteArray(80) { it.toByte() })
        assertEquals(QrParseResult.NotDarkMessage, codec.parse(darkm))
    }

    // ------------------------------------------------------- section 1.6 plaintext negatives

    @Test
    fun `plaintext level negatives report malformed`() = runBlocking {
        val pw = byteArrayOf(0x70, 0x77) // "pw"

        // nameLen = 65 (> 64)
        assertOpenIs(
            QrOpenResult.Malformed,
            "nameLen 65",
            byteArrayOf(65) + ByteArray(65) { 0x61 } + pw
        )
        // nameLen = pt.size - 1: no passphrase byte left
        assertOpenIs(
            QrOpenResult.Malformed,
            "nameLen leaves no passphrase",
            byteArrayOf(3) + byteArrayOf(0x61, 0x62, 0x63)
        )
        // passphrase 129 bytes (> 128)
        assertOpenIs(
            QrOpenResult.Malformed,
            "passphrase 129 B",
            byteArrayOf(0) + ByteArray(129) { 0x78 }
        )
        // name containing 0x0A
        assertOpenIs(
            QrOpenResult.Malformed,
            "line feed inside the name",
            byteArrayOf(5) + byteArrayOf(0x41, 0x6C, 0x0A, 0x63, 0x65) + pw
        )
        // name containing 0x00 (not whitespace, survives the trim)
        assertOpenIs(
            QrOpenResult.Malformed,
            "nul inside the name",
            byteArrayOf(3) + byteArrayOf(0x41, 0x00, 0x6C) + pw
        )
        // name containing 0x7F
        assertOpenIs(
            QrOpenResult.Malformed,
            "delete char inside the name",
            byteArrayOf(3) + byteArrayOf(0x41, 0x7F, 0x6C) + pw
        )
        // non-UTF-8 passphrase FF FE
        assertOpenIs(
            QrOpenResult.Malformed,
            "non utf8 passphrase",
            byteArrayOf(0) + byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        )
        // non-UTF-8 name
        assertOpenIs(
            QrOpenResult.Malformed,
            "non utf8 name",
            byteArrayOf(2) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + pw
        )
        // a truncated UTF-8 sequence must not become U+FFFD
        assertOpenIs(
            QrOpenResult.Malformed,
            "truncated utf8 passphrase",
            byteArrayOf(0) + byteArrayOf(0xD0.toByte())
        )
        // an overlong encoding of '/' must be rejected too
        assertOpenIs(
            QrOpenResult.Malformed,
            "overlong utf8 passphrase",
            byteArrayOf(0) + byteArrayOf(0xC0.toByte(), 0xAF.toByte())
        )
    }

    @Test
    fun `a body below the 47 byte floor is rejected by parse`() = runBlocking {
        // A 1-byte plaintext yields a 46-byte body: below the size floor of section 1.2.
        val text = sealRaw(byteArrayOf(0))
        assertEquals(46, bodyBytesOf(text).size)
        assertEquals(QrParseResult.Malformed, codec.parse(text))
    }

    @Test
    fun `an empty name opens to a null name`() = runBlocking {
        assertOpenIs(
            QrOpenResult.Ok(null, "pw"),
            "nameLen 0",
            byteArrayOf(0) + byteArrayOf(0x70, 0x77)
        )
        // A name of only spaces is empty after trimming.
        assertOpenIs(
            QrOpenResult.Ok(null, "pw"),
            "blank name",
            byteArrayOf(2) + byteArrayOf(0x20, 0x20) + byteArrayOf(0x70, 0x77)
        )
    }

    @Test
    fun `the exact byte boundaries are accepted`() = runBlocking {
        // nameLen 0 with a 128-byte passphrase.
        assertOpenIs(
            QrOpenResult.Ok(null, "x".repeat(128)),
            "passphrase 128 B",
            byteArrayOf(0) + ByteArray(128) { 0x78 }
        )
        // Shortest legal plaintext: no name, one passphrase byte (47-byte body).
        assertOpenIs(
            QrOpenResult.Ok(null, "x"),
            "minimum plaintext",
            byteArrayOf(0, 0x78)
        )
    }

    @Test
    fun `the passphrase is returned verbatim without trimming`() = runBlocking {
        val padded = "  spaced  key  "
        val text = codec.encode("Bob", padded, V1.pin, hex(V1.saltHex), hex(V1.nonceHex))
        assertEquals(QrOpenResult.Ok("Bob", padded), codec.open(parsedOf(text), V1.pin))
    }

    @Test
    fun `the name is trimmed by the encoder`() = runBlocking {
        val text = codec.encode(
            "   Alice   ",
            V1.passphrase,
            V1.pin,
            hex(V1.saltHex),
            hex(V1.nonceHex)
        )
        assertEquals("trimming the name must reproduce vector 1", V1.encoded, text)
    }

    // ---------------------------------------------------------------- open guards

    @Test
    fun `open rejects a pin that is not six ascii digits`() = runBlocking {
        val parsed = parsedOf(V1.encoded)
        val arabicIndic = codePoint(0x0661) + codePoint(0x0662) + codePoint(0x0663) + "456"
        for (pin in listOf("", "1", "12345", "1234567", "12345a", "12 456", arabicIndic)) {
            val label = "pin of length " + pin.length
            assertEquals(label, QrOpenResult.Malformed, codec.open(parsed, pin))
        }
    }

    @Test
    fun `open rejects a foreign kdf id`() = runBlocking {
        val parsed = parsedOf(V1.encoded)
        val foreign = ParsedQr(0x02, parsed.salt, parsed.nonce, parsed.ciphertextAndTag)
        assertEquals(QrOpenResult.Malformed, codec.open(foreign, V1.pin))
    }

    // ---------------------------------------------------------------- encode guards

    @Test
    fun `encode rejects an empty passphrase`() {
        expectEncodeError(QrEncodeError.NO_PASSPHRASE) { codec.encode("Alice", "", "123456") }
    }

    @Test
    fun `encode rejects a passphrase longer than 128 bytes`() {
        expectEncodeError(QrEncodeError.PASSPHRASE_TOO_LONG) {
            codec.encode("Alice", "x".repeat(129), "123456")
        }
        // 65 Cyrillic letters are only 65 characters but 130 bytes.
        expectEncodeError(QrEncodeError.PASSPHRASE_TOO_LONG) {
            codec.encode("Alice", codePoint(0x0430).repeat(65), "123456")
        }
    }

    @Test
    fun `encode rejects a name longer than 64 bytes`() {
        expectEncodeError(QrEncodeError.NAME_TOO_LONG) {
            codec.encode("a".repeat(65), "key", "123456")
        }
        // 33 Cyrillic capitals are 66 bytes.
        expectEncodeError(QrEncodeError.NAME_TOO_LONG) {
            codec.encode(codePoint(0x0410).repeat(33), "key", "123456")
        }
    }

    @Test
    fun `encode rejects a pin that is not six digits`() {
        for (pin in listOf("", "1", "12345", "1234567", "12345a", "12 456", "-12345")) {
            expectEncodeError(QrEncodeError.INVALID_PIN) { codec.encode("Alice", "key", pin) }
        }
    }

    // ---------------------------------------------------------------- generators

    @Test
    fun `generatePin returns six digits`() {
        val pinRegex = Regex("^[0-9]{6}$")
        val seen = HashSet<String>()
        repeat(300) {
            val pin = codec.generatePin()
            assertTrue("bad pin: " + pin, pinRegex.matches(pin))
            seen.add(pin)
        }
        assertTrue("generatePin must not be constant", seen.size > 100)
    }

    @Test
    fun `randomNonce returns twelve fresh bytes`() {
        val a = codec.randomNonce()
        val b = codec.randomNonce()
        assertEquals(12, a.size)
        assertEquals(12, b.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `randomSalt returns sixteen fresh bytes`() {
        val a = codec.randomSalt()
        val b = codec.randomSalt()
        assertEquals(16, a.size)
        assertEquals(16, b.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `round trip with generated salt nonce and pin`() = runBlocking {
        val pin = codec.generatePin()
        val name = "  " + codePoint(0x0410) + codePoint(0x043D) + codePoint(0x043D) +
            codePoint(0x0430) + "  "
        val passphrase = "correct horse " + codePoint(0x1F511) + " 2026"
        val text = codec.encode(name, passphrase, pin)
        assertTrue(text.startsWith(PREFIX_1))
        val parsed = parsedOf(text)
        assertEquals(ChatQrCodec.KDF_ID_PBKDF2_SHA512, parsed.kdfId)
        assertEquals(16, parsed.salt.size)
        assertEquals(12, parsed.nonce.size)
        assertEquals(QrOpenResult.Ok(name.trim(), passphrase), codec.open(parsed, pin))
        // A second code for the same chat uses a fresh salt and nonce.
        val again = codec.encode(name, passphrase, pin)
        assertFalse(text == again)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun assertEncodesToSpec(v: Vector) {
        val text = codec.encode(v.name, v.passphrase, v.pin, hex(v.saltHex), hex(v.nonceHex))
        assertEquals(v.label + ": encoded text", v.encoded, text)
    }

    private suspend fun assertOpensToSpec(v: Vector) {
        val parsed = parsedOf(v.encoded)
        assertEquals(v.label + ": kdfId", ChatQrCodec.KDF_ID_PBKDF2_SHA512, parsed.kdfId)
        assertEquals(v.label + ": salt", v.saltHex, toHex(parsed.salt))
        assertEquals(v.label + ": nonce", v.nonceHex, toHex(parsed.nonce))
        assertEquals(
            v.label + ": ciphertext and tag length",
            v.plaintextHex.length / 2 + 16,
            parsed.ciphertextAndTag.size
        )
        assertEquals(
            v.label + ": opened payload",
            QrOpenResult.Ok(v.name, v.passphrase),
            codec.open(parsed, v.pin)
        )
    }

    /** Seals an arbitrary plaintext with vector 1's pin, salt and nonce, bypassing encode(). */
    private suspend fun sealRaw(plaintext: ByteArray): String {
        val salt = hex(V1.saltHex)
        val nonce = hex(V1.nonceHex)
        val key = sharedDeriver.deriveKey(V1.pin.toCharArray(), salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD(
                "darkmessage-qr-v1".toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0x01) + salt + nonce
            )
            return textOf(byteArrayOf(0x01) + salt + nonce + cipher.doFinal(plaintext))
        } finally {
            key.fill(0)
        }
    }

    private suspend fun assertOpenIs(
        expected: QrOpenResult,
        label: String,
        plaintext: ByteArray
    ) {
        val text = sealRaw(plaintext)
        assertTrue(label + ": must parse", codec.parse(text) is QrParseResult.Ok)
        assertEquals(label, expected, codec.open(parsedOf(text), V1.pin))
    }

    private fun parsedOf(text: String): ParsedQr {
        val result = codec.parse(text)
        if (result !is QrParseResult.Ok) {
            fail("expected QrParseResult.Ok but got " + result)
            throw AssertionError("unreachable")
        }
        return result.parsed
    }

    private fun expectEncodeError(expected: QrEncodeError, block: suspend () -> String) {
        try {
            runBlocking { block() }
            fail("expected ChatQrEncodeException(" + expected + ")")
        } catch (e: ChatQrEncodeException) {
            assertEquals(expected, e.error)
        }
    }

    private fun bodyStringOf(text: String): String = text.substring(PREFIX_1.length)

    private fun bodyBytesOf(text: String): ByteArray =
        Base64.getUrlDecoder().decode(bodyStringOf(text))

    private fun textOf(body: ByteArray): String =
        PREFIX_1 + Base64.getUrlEncoder().withoutPadding().encodeToString(body)

    private fun withBodyByte(text: String, index: Int, transform: (Byte) -> Byte): String {
        val body = bodyBytesOf(text)
        body[index] = transform(body[index])
        return textOf(body)
    }

    companion object {
        private val sharedDeriver = CachingKeyDeriver()

        private const val PREFIX_1 = "darkmessage://chat/1/"

        private const val ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        private const val HEX_DIGITS = "0123456789abcdef"

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun toHex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                out.append(HEX_DIGITS[(b.toInt() shr 4) and 0x0F])
                out.append(HEX_DIGITS[b.toInt() and 0x0F])
            }
            return out.toString()
        }

        private fun utf8(hexString: String): String = String(hex(hexString), Charsets.UTF_8)

        private fun codePoint(cp: Int): String = String(Character.toChars(cp))

        /** Vector 1 - ASCII name and passphrase. */
        private val V1 = Vector(
            label = "V1 ascii",
            name = "Alice",
            passphrase = "correct horse battery staple",
            pin = "123456",
            saltHex = "000102030405060708090a0b0c0d0e0f",
            nonceHex = "101112131415161718191a1b",
            keyHex = "ff2c26d5c53a0e8c375dbc125eb3b89a9aa4553a01ac06dfba5d0d8862991c8b",
            plaintextHex = "05416c696365636f727265637420686f727365206261747465727920737461706c65",
            bodyBytes = 79,
            bodyChars = 106,
            textChars = 127,
            fingerprint = "C4BB CB1F",
            encoded = "darkmessage://chat/1/AQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs3HFnUd8n3f" +
                "P-Yq-jPTMjwxUTsJ2R_7_-6hQrR8Il--WfkvphyoEPPU8KzyKY1JuvllQ"
        )

        /** Vector 2 - Russian name, Russian passphrase, PIN with leading zeros. */
        private val V2 = Vector(
            label = "V2 russian",
            name = utf8("d091d0b0d0b1d183d188d0bad0b0"),
            passphrase = utf8("d182d0b0d0b9d0bdd18bd0b920d0bad0bbd18ed1872032303236"),
            pin = "007042",
            saltHex = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
            nonceHex = "b0b1b2b3b4b5b6b7b8b9babb",
            keyHex = "d1d3bda3055e92d63365ac33ac13cf894ecf82cfad2b362b1e6b6c5ec7df3ca0",
            plaintextHex = "0ed091d0b0d0b1d183d188d0bad0b0d182d0b0d0b9d0bdd18bd0b920d0bad0bb" +
                "d18ed1872032303236",
            bodyBytes = 86,
            bodyChars = 115,
            textChars = 136,
            fingerprint = "C74C 859F",
            encoded = "darkmessage://chat/1/AaChoqOkpaanqKmqq6ytrq-wsbKztLW2t7i5uru4T5ovrokN2" +
                "aQWLE71l-Vbrh1iM6M5BKJzQTxzUbPbR5jZsG21VHSJLhORSORJ53hgbfugwfSUAtw"
        )

        /** Vector 3 - passphrase with spaces and emoji. */
        private val V3 = Vector(
            label = "V3 emoji",
            name = "Bob",
            passphrase = utf8("6d792073656372657420f09f94912070687261736520f09f9a80"),
            pin = "999999",
            saltHex = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
            nonceHex = "e0e1e2e3e4e5e6e7e8e9eaeb",
            keyHex = "15089200c5cdf8d27986b705282f394cceaa9401725481e0570946bfef2984c5",
            plaintextHex = "03426f626d792073656372657420f09f94912070687261736520f09f9a80",
            bodyBytes = 75,
            bodyChars = 100,
            textChars = 121,
            fingerprint = "DF58 6381",
            encoded = "darkmessage://chat/1/AfDx8vP09fb3-Pn6-_z9_v_g4eLj5OXm5-jp6uvklGnz1-I7r" +
                "l5tWfD8Azfwh0AVVkw0jHkYN3h2G3a8IwnOPfwWVk3TbJlLDnp0"
        )

        /** Vector 4 - maximum length: name exactly 64 B, passphrase exactly 128 B. */
        private val V4 = Vector(
            label = "V4 max length",
            name = (0x0410..0x042F).joinToString("") { codePoint(it) },
            passphrase = ALPHABET + "-" + ALPHABET + codePoint(0x0451) + "!",
            pin = "000000",
            saltHex = "ffffffffffffffffffffffffffffffff",
            nonceHex = "ffffffffffffffffffffffff",
            keyHex = "86f86159b77d64df056024b7fa967580a7fc9ece27f18e484020857fb55b1d22",
            plaintextHex = "40" +
                "d090d091d092d093d094d095d096d097d098d099d09ad09bd09cd09dd09ed09f" +
                "d0a0d0a1d0a2d0a3d0a4d0a5d0a6d0a7d0a8d0a9d0aad0abd0acd0add0aed0af" +
                "4142434445464748494a4b4c4d4e4f505152535455565758595a" +
                "6162636465666768696a6b6c6d6e6f707172737475767778797a" +
                "30313233343536373839" +
                "2d" +
                "4142434445464748494a4b4c4d4e4f505152535455565758595a" +
                "6162636465666768696a6b6c6d6e6f707172737475767778797a" +
                "30313233343536373839" +
                "d19121",
            bodyBytes = 238,
            bodyChars = 318,
            textChars = 339,
            fingerprint = "F12D 440C",
            encoded = "darkmessage://chat/1/Af____________________________________9T1ZQgemktx" +
                "1T3kQFquXa_cu3b2ImHe-x7RQMltUOoQjD_sfFzlrmSYur4jKah70jWuMGjb42hLIr8qIeFdj" +
                "VvFl5UA0JowT7Nu9jf0nLYjd-sGJgtrW3dexliFgT3kF4g8_Mg_q1vmNTMr1Ppe2UpDr27Pi4" +
                "uN-CDkpLCrqrHR3OhhJ62k3MQula3fptM8W8X0MKsTq1H9dufXZchIwka6fSIvbe9Yhkflmyr" +
                "mgSLq8XLc3yN7mWq4PidRcQsAKHnRZtUyn2gKrnrXsqrAcNEaw"
        )

        private val VECTORS = listOf(V1, V2, V3, V4)

        /** Printed verbatim in section 1.6: a v1 body under path version 2. */
        private const val SPEC_PATH_VERSION_2 =
            "darkmessage://chat/2/AQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs3HFnUd8n3fP-Yq-jPT" +
                "MjwxUTsJ2R_7_-6hQrR8Il--WfkvphyoEPPU8KzyKY1JuvllQ"

        /** Printed verbatim in section 1.6: kdfId 0x02 inside a v1 path. */
        private const val SPEC_KDF_ID_2 =
            "darkmessage://chat/1/AgABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhs3HFnUd8n3fP-Yq-jPT" +
                "MjwxUTsJ2R_7_-6hQrR8Il--WfkvphyoEPPU8KzyKY1JuvllQ"
    }
}
