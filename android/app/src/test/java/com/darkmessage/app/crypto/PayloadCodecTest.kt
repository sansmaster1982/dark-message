package com.darkmessage.app.crypto

import com.darkmessage.app.core.constants.CryptoConstants
import com.darkmessage.app.data.model.ContentType
import com.darkmessage.app.data.model.MessagePayload
import com.darkmessage.app.data.model.PayloadVersion
import org.junit.Assert.*
import org.junit.Test

class PayloadCodecTest {

    /** Built from char codes so this file's own line endings cannot change it. */
    private val CR_LF = "" + 13.toChar() + 10.toChar()

    @Test
    fun `encode then decode returns identical payload`() {
        val original = MessagePayload(
            version = PayloadVersion.V1,
            contentType = ContentType.TEXT,
            salt = ByteArray(16) { it.toByte() },
            nonce = ByteArray(12) { (it + 100).toByte() },
            ciphertext = ByteArray(32) { (it + 50).toByte() } // must be >= 16 (GCM tag)
        )

        val encoded = PayloadCodec.encode(original)
        val decoded = PayloadCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode produces correct binary layout`() {
        val salt = ByteArray(16) { 0xAA.toByte() }
        val nonce = ByteArray(12) { 0xBB.toByte() }
        val ciphertext = byteArrayOf(1, 2, 3, 4, 5)

        val payload = MessagePayload(PayloadVersion.V1, ContentType.IMAGE, salt, nonce, ciphertext)
        val encoded = PayloadCodec.encode(payload)

        // Total = 1 + 1 + 16 + 12 + 5 = 35
        assertEquals(35, encoded.size)
        assertEquals(0x01.toByte(), encoded[0])  // version
        assertEquals(0x02.toByte(), encoded[1])  // contentType IMAGE
        // salt starts at offset 2
        assertEquals(0xAA.toByte(), encoded[2])
        // nonce starts at offset 18
        assertEquals(0xBB.toByte(), encoded[18])
        // ciphertext starts at offset 30
        assertEquals(1.toByte(), encoded[30])
        assertEquals(5.toByte(), encoded[34])
    }

    @Test
    fun `decode returns null for data shorter than MIN_PAYLOAD_SIZE`() {
        val tooShort = ByteArray(CryptoConstants.MIN_PAYLOAD_SIZE - 1)
        assertNull(PayloadCodec.decode(tooShort))
    }

    @Test
    fun `decode returns null for unknown version`() {
        val data = ByteArray(CryptoConstants.MIN_PAYLOAD_SIZE)
        data[0] = 0xFF.toByte()  // invalid version
        data[1] = 0x01           // valid content type
        assertNull(PayloadCodec.decode(data))
    }

    @Test
    fun `decode returns null for unknown content type`() {
        val data = ByteArray(CryptoConstants.MIN_PAYLOAD_SIZE)
        data[0] = 0x01           // valid version
        data[1] = 0xFF.toByte()  // invalid content type
        assertNull(PayloadCodec.decode(data))
    }

    @Test
    fun `roundtrip all content types`() {
        for (ct in ContentType.entries) {
            val original = MessagePayload(
                PayloadVersion.V1, ct,
                ByteArray(16) { 0x11 }, ByteArray(12) { 0x22 },
                ByteArray(100) { it.toByte() }
            )
            val decoded = PayloadCodec.decode(PayloadCodec.encode(original))
            assertEquals("Failed for $ct", original, decoded)
        }
    }

    @Test
    fun `encode with empty ciphertext`() {
        val payload = MessagePayload(
            PayloadVersion.V1, ContentType.TEXT,
            ByteArray(16), ByteArray(12), ByteArray(0)
        )
        val encoded = PayloadCodec.encode(payload)
        assertEquals(30, encoded.size) // header only
    }

    @Test
    fun `roundtrip large ciphertext`() {
        val largeCiphertext = ByteArray(1_000_000) { (it % 256).toByte() }
        val original = MessagePayload(
            PayloadVersion.V1, ContentType.DOCUMENT,
            ByteArray(16) { 0x33 }, ByteArray(12) { 0x44 },
            largeCiphertext
        )
        val decoded = PayloadCodec.decode(PayloadCodec.encode(original))
        assertNotNull(decoded)
        assertArrayEquals(largeCiphertext, decoded!!.ciphertext)
    }

    // --- Leading transport noise (found on real phones, 2026-09-13) ---

    private fun samplePayload() = MessagePayload(
        version = PayloadVersion.V1,
        contentType = ContentType.IMAGE,
        salt = ByteArray(16) { it.toByte() },
        nonce = ByteArray(12) { (it + 100).toByte() },
        ciphertext = ByteArray(40) { (it + 7).toByte() }
    )

    @Test
    fun `a payload preceded by CR LF still decodes`() {
        // Every .darkm file that reached an Android phone from an iPhone carried
        // exactly these two bytes in front and was rejected as a bad format.
        val encoded = PayloadCodec.encode(samplePayload())
        val mangled = byteArrayOf(0x0D, 0x0A) + encoded

        assertEquals(samplePayload(), PayloadCodec.decode(mangled))
    }

    @Test
    fun `spaces tabs and newlines in front are all skipped`() {
        val encoded = PayloadCodec.encode(samplePayload())
        val mangled = byteArrayOf(0x20, 0x09, 0x0A, 0x0D, 0x20) + encoded

        assertEquals(samplePayload(), PayloadCodec.decode(mangled))
    }

    @Test
    fun `a ciphertext ending in a newline is left alone`() {
        // Nothing may be stripped from the end: the last byte is part of the GCM
        // tag and 0x0A is a perfectly legal value for it.
        val payload = samplePayload().copy(
            ciphertext = ByteArray(40) { if (it == 39) 0x0A else (it + 7).toByte() }
        )
        val decoded = PayloadCodec.decode(PayloadCodec.encode(payload))

        assertEquals(payload, decoded)
        assertEquals(0x0A.toByte(), decoded!!.ciphertext.last())
    }

    @Test
    fun `nothing but whitespace is still rejected`() {
        assertNull(PayloadCodec.decode(ByteArray(64) { 0x0A }))
    }

    @Test
    fun `noise in front does not make a too short payload decode`() {
        val tooShort = byteArrayOf(0x0D, 0x0A, 0x01, 0x01) + ByteArray(10)

        assertNull(PayloadCodec.decode(tooShort))
    }

    // --- base64 that travelled as text ---

    @Test
    fun `line wrapped base64 normalises to the unwrapped form`() {
        val plain = "AQFhYmNkZWZnaGlqa2xtbm9wcXJzdHV2"
        val wrapped = "AQFhYmNkZWZn" + CR_LF + "aGlqa2xtbm9w" + CR_LF + "cXJzdHV2"

        assertEquals(plain, PayloadCodec.normalizeBase64(wrapped))
    }

    @Test
    fun `the url safe alphabet is accepted`() {
        // "----Pz8_" is the URL-safe spelling of "++++Pz8/"
        assertEquals("++++Pz8/", PayloadCodec.normalizeBase64("----Pz8_"))
    }

    @Test
    fun `missing padding is restored`() {
        assertEquals("YWJjZA==", PayloadCodec.normalizeBase64("YWJjZA"))
        assertEquals("YWJj", PayloadCodec.normalizeBase64("YWJj"))
    }

    @Test
    fun `text that is not base64 at all is rejected`() {
        assertNull(PayloadCodec.normalizeBase64("Привет, это не шифр"))
        assertNull(PayloadCodec.normalizeBase64("   "))
        // A length that leaves one character over is impossible in base64.
        assertNull(PayloadCodec.normalizeBase64("YWJjZ"))
    }

    // --- A file the transport turned into text ---

    @Test
    fun `a byte order mark in front is skipped`() {
        val encoded = PayloadCodec.encode(samplePayload())
        val mangled = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + encoded

        assertEquals(samplePayload(), PayloadCodec.decode(mangled))
    }

    @Test
    fun `a byte order mark plus a newline is skipped`() {
        val encoded = PayloadCodec.encode(samplePayload())
        val mangled = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x0D, 0x0A) + encoded

        assertEquals(samplePayload(), PayloadCodec.decode(mangled))
    }

    @Test
    fun `three bytes that only look like a mark do not eat the payload`() {
        // 0xEF 0xBB followed by something else must not be skipped.
        val encoded = PayloadCodec.encode(samplePayload())
        val mangled = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0x41) + encoded

        assertNull(PayloadCodec.decode(mangled))
    }

    @Test
    fun `a damaged file is still refused`() {
        assertNull(PayloadCodec.decodeFromBase64Text(ByteArray(200) { 0x41 }))
        assertNull(PayloadCodec.decodeFromBase64Text(ByteArray(10)))
    }
}
