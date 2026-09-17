package com.darkmessage.app.crypto

import com.darkmessage.app.data.model.ContentType
import com.darkmessage.app.data.model.DecryptionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the two platforms do with a DOCUMENT frame that is not perfect.
 *
 * The frame is [nameLen:2 big-endian][name UTF-8][file bytes]. Every case below has a matching
 * XCTest on iOS asserting the same answer, because the same payload must become the same file on
 * both phones. They diverged twice: iOS threw a whole name away for one malformed byte where Java
 * substitutes a replacement character, and a zero-length name left Android handing the user a file
 * with the two length bytes still glued to the front.
 */
class DocumentFrameTest {

    private lateinit var engine: CryptoEngineImpl

    @Before
    fun setUp() {
        engine = CryptoEngineImpl(Pbkdf2KeyDeriver())
    }

    private val pdf = "%PDF-1.4\ntrailer<</Root 1 0 R>>".toByteArray(Charsets.US_ASCII)

    private fun frame(nameBytes: ByteArray, body: ByteArray, declaredLength: Int = -1): ByteArray {
        val length = if (declaredLength >= 0) declaredLength else nameBytes.size
        return byteArrayOf(((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) +
            nameBytes + body
    }

    private fun roundTrip(plain: ByteArray, passphrase: String): DecryptionResult = runBlocking {
        val encrypted = engine.encrypt(plain, passphrase.toCharArray(), ContentType.DOCUMENT)
        engine.decrypt(encrypted, passphrase.toCharArray())
    }

    @Test
    fun `an ordinary frame gives the name and the file`() {
        val name = "Отчёт за сентябрь.pdf"
        val result = roundTrip(frame(name.toByteArray(Charsets.UTF_8), pdf), "ключ-1")

        assertTrue(result is DecryptionResult.DocumentMessage)
        result as DecryptionResult.DocumentMessage
        assertEquals(name, result.fileName)
        assertArrayEquals(pdf, result.documentBytes)
    }

    @Test
    fun `one damaged byte in the name does not throw the whole name away`() {
        // 0xFF can never appear in UTF-8. Java substitutes U+FFFD and keeps going; Swift's
        // String(data:encoding:) returns nil, which used to turn the whole name into "document".
        val damaged = "отчет".toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte()) +
            ".pdf".toByteArray(Charsets.UTF_8)
        val result = roundTrip(frame(damaged, pdf), "ключ-2")

        result as DecryptionResult.DocumentMessage
        assertTrue("the name vanished instead of arriving damaged: ${result.fileName}",
            result.fileName.startsWith("отчет"))
        assertTrue("the extension was lost with it", result.fileName.endsWith(".pdf"))
        assertArrayEquals(pdf, result.documentBytes)
    }

    @Test
    fun `a zero-length name still takes the two length bytes off the file`() {
        val result = roundTrip(frame(ByteArray(0), pdf), "ключ-3")

        result as DecryptionResult.DocumentMessage
        assertEquals("document", result.fileName)
        assertArrayEquals("the length bytes are still glued to the file", pdf, result.documentBytes)
    }

    @Test
    fun `a length that cannot fit means the bytes are not a frame at all`() {
        // Declared 5000 bytes of name in a payload that holds nothing like it: this is not a name
        // frame, so the whole plaintext is the file. iOS decides the same.
        val plain = frame("короткое".toByteArray(Charsets.UTF_8), pdf, declaredLength = 5000)
        val result = roundTrip(plain, "ключ-4")

        result as DecryptionResult.DocumentMessage
        assertEquals("document", result.fileName)
        assertArrayEquals(plain, result.documentBytes)
    }

    @Test
    fun `a name that fills the whole payload leaves an empty file`() {
        val name = "только имя.pdf".toByteArray(Charsets.UTF_8)
        val result = roundTrip(frame(name, ByteArray(0)), "ключ-5")

        result as DecryptionResult.DocumentMessage
        assertEquals("только имя.pdf", result.fileName)
        assertEquals(0, result.documentBytes.size)
    }

    @Test
    fun `a name of exactly 65535 bytes is still a name`() {
        val name = "и".repeat(30000).toByteArray(Charsets.UTF_8) // 60000 bytes, two per letter
        val result = roundTrip(frame(name, pdf), "ключ-6")

        result as DecryptionResult.DocumentMessage
        assertEquals(60000, result.fileName.toByteArray(Charsets.UTF_8).size)
        assertArrayEquals(pdf, result.documentBytes)
    }
}
