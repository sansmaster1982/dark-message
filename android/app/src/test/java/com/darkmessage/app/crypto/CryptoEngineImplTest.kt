package com.darkmessage.app.crypto

import com.darkmessage.app.data.model.ContentType
import com.darkmessage.app.data.model.DecryptionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Full encrypt-decrypt integration tests using the PBKDF2 key deriver
 * (the same deriver the app uses in production - see CryptoModule).
 */
class CryptoEngineImplTest {

    private lateinit var engine: CryptoEngineImpl

    @Before
    fun setUp() {
        engine = CryptoEngineImpl(Pbkdf2KeyDeriver())
    }

    // ==================== TEXT ====================

    @Test
    fun `encrypt-decrypt text roundtrip`() = runBlocking {
        val original = "Hello, Dark Message!"
        val passphrase = "mySecretPass".toCharArray()

        val encrypted = engine.encrypt(
            original.toByteArray(Charsets.UTF_8),
            passphrase,
            ContentType.TEXT
        )

        val result = engine.decrypt(encrypted, "mySecretPass".toCharArray())

        assertTrue(result is DecryptionResult.TextMessage)
        assertEquals(original, (result as DecryptionResult.TextMessage).plaintext)
    }

    @Test
    fun `encrypt-decrypt empty text`() = runBlocking {
        val original = ""
        val passphrase = "pass".toCharArray()

        val encrypted = engine.encrypt(
            original.toByteArray(Charsets.UTF_8),
            passphrase,
            ContentType.TEXT
        )

        val result = engine.decrypt(encrypted, "pass".toCharArray())
        assertTrue(result is DecryptionResult.TextMessage)
        assertEquals(original, (result as DecryptionResult.TextMessage).plaintext)
    }

    @Test
    fun `encrypt-decrypt unicode text`() = runBlocking {
        val original = "\u0428\u0438\u0444\u0440\u043E\u0432\u0430\u043D\u0438\u0435 \uD83D\uDD10 \u6697\u53F7 \u0627\u0644\u062A\u0634\u0641\u064A\u0631"
        val passphrase = "\u041F\u0430\u0440\u043E\u043B\u044C123".toCharArray()

        val encrypted = engine.encrypt(
            original.toByteArray(Charsets.UTF_8),
            passphrase,
            ContentType.TEXT
        )

        val result = engine.decrypt(encrypted, "\u041F\u0430\u0440\u043E\u043B\u044C123".toCharArray())
        assertTrue(result is DecryptionResult.TextMessage)
        assertEquals(original, (result as DecryptionResult.TextMessage).plaintext)
    }

    @Test
    fun `encrypt-decrypt long text`() = runBlocking {
        val original = "A".repeat(100_000)
        val passphrase = "longTextPass".toCharArray()

        val encrypted = engine.encrypt(
            original.toByteArray(Charsets.UTF_8),
            passphrase,
            ContentType.TEXT
        )

        val result = engine.decrypt(encrypted, "longTextPass".toCharArray())
        assertTrue(result is DecryptionResult.TextMessage)
        assertEquals(original, (result as DecryptionResult.TextMessage).plaintext)
    }

    // ==================== IMAGE ====================

    @Test
    fun `encrypt-decrypt image roundtrip`() = runBlocking {
        // Simulate a small PNG-like file (header + random bytes)
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val imageData = pngHeader + ByteArray(5000) { (it % 256).toByte() }
        val passphrase = "imagePass!".toCharArray()

        val encrypted = engine.encrypt(imageData, passphrase, ContentType.IMAGE)

        val result = engine.decrypt(encrypted, "imagePass!".toCharArray())
        assertTrue(result is DecryptionResult.ImageMessage)
        assertArrayEquals(imageData, (result as DecryptionResult.ImageMessage).imageBytes)
    }

    @Test
    fun `encrypt-decrypt large image (1MB)`() = runBlocking {
        val largeImage = ByteArray(1_000_000) { (it % 256).toByte() }
        val passphrase = "bigImg".toCharArray()

        val encrypted = engine.encrypt(largeImage, passphrase, ContentType.IMAGE)

        val result = engine.decrypt(encrypted, "bigImg".toCharArray())
        assertTrue(result is DecryptionResult.ImageMessage)
        assertArrayEquals(largeImage, (result as DecryptionResult.ImageMessage).imageBytes)
    }

    // ==================== DOCUMENT ====================

    @Test
    fun `encrypt-decrypt document with filename`() = runBlocking {
        val fileName = "report.pdf"
        val docContent = ByteArray(2048) { (it % 256).toByte() }

        // Build document payload: [nameLen:2B][nameUTF8][docBytes]
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val docPayload = ByteArray(2 + nameBytes.size + docContent.size)
        docPayload[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
        docPayload[1] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, docPayload, 2, nameBytes.size)
        System.arraycopy(docContent, 0, docPayload, 2 + nameBytes.size, docContent.size)

        val passphrase = "docPass".toCharArray()

        val encrypted = engine.encrypt(docPayload, passphrase, ContentType.DOCUMENT)

        val result = engine.decrypt(encrypted, "docPass".toCharArray())
        assertTrue(result is DecryptionResult.DocumentMessage)
        val doc = result as DecryptionResult.DocumentMessage
        assertEquals(fileName, doc.fileName)
        assertArrayEquals(docContent, doc.documentBytes)
    }

    @Test
    fun `encrypt-decrypt document with unicode filename`() = runBlocking {
        val fileName = "\u041E\u0442\u0447\u0451\u0442_2024.xlsx"
        val docContent = "spreadsheet data".toByteArray()

        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val docPayload = ByteArray(2 + nameBytes.size + docContent.size)
        docPayload[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
        docPayload[1] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, docPayload, 2, nameBytes.size)
        System.arraycopy(docContent, 0, docPayload, 2 + nameBytes.size, docContent.size)

        val passphrase = "unicodeDoc".toCharArray()

        val encrypted = engine.encrypt(docPayload, passphrase, ContentType.DOCUMENT)

        val result = engine.decrypt(encrypted, "unicodeDoc".toCharArray())
        assertTrue(result is DecryptionResult.DocumentMessage)
        val doc = result as DecryptionResult.DocumentMessage
        assertEquals(fileName, doc.fileName)
        assertArrayEquals(docContent, doc.documentBytes)
    }

    @Test
    fun `encrypt-decrypt document with long filename`() = runBlocking {
        val fileName = "a".repeat(1000) + ".txt"
        val docContent = "content".toByteArray()

        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val docPayload = ByteArray(2 + nameBytes.size + docContent.size)
        docPayload[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
        docPayload[1] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, docPayload, 2, nameBytes.size)
        System.arraycopy(docContent, 0, docPayload, 2 + nameBytes.size, docContent.size)

        val passphrase = "longName".toCharArray()
        val encrypted = engine.encrypt(docPayload, passphrase, ContentType.DOCUMENT)
        val result = engine.decrypt(encrypted, "longName".toCharArray())

        assertTrue(result is DecryptionResult.DocumentMessage)
        assertEquals(fileName, (result as DecryptionResult.DocumentMessage).fileName)
    }

    // ==================== ERROR CASES ====================

    @Test
    fun `wrong passphrase returns BAD_PASSPHRASE error`() = runBlocking {
        val encrypted = engine.encrypt(
            "secret".toByteArray(),
            "correctPass".toCharArray(),
            ContentType.TEXT
        )

        val result = engine.decrypt(encrypted, "wrongPass".toCharArray())
        assertTrue(result is DecryptionResult.Error)
        assertEquals("BAD_PASSPHRASE", (result as DecryptionResult.Error).message)
    }

    @Test
    fun `corrupt payload returns INVALID_FORMAT error`() = runBlocking {
        val result = engine.decrypt(ByteArray(5), "pass".toCharArray())
        assertTrue(result is DecryptionResult.Error)
        assertEquals("INVALID_FORMAT", (result as DecryptionResult.Error).message)
    }

    @Test
    fun `tampered ciphertext returns BAD_PASSPHRASE error`() = runBlocking {
        val encrypted = engine.encrypt(
            "test data".toByteArray(),
            "pass".toCharArray(),
            ContentType.TEXT
        )

        // Flip a byte in the ciphertext (after 30-byte header)
        encrypted[35] = (encrypted[35].toInt() xor 0xFF).toByte()

        val result = engine.decrypt(encrypted, "pass".toCharArray())
        assertTrue(result is DecryptionResult.Error)
        // GCM tag verification fails = AEADBadTagException = BAD_PASSPHRASE
        assertEquals("BAD_PASSPHRASE", (result as DecryptionResult.Error).message)
    }

    @Test
    fun `invalid version in payload returns INVALID_FORMAT`() = runBlocking {
        val encrypted = engine.encrypt(
            "test".toByteArray(),
            "pass".toCharArray(),
            ContentType.TEXT
        )
        encrypted[0] = 0xFF.toByte() // corrupt version byte

        val result = engine.decrypt(encrypted, "pass".toCharArray())
        assertTrue(result is DecryptionResult.Error)
        assertEquals("INVALID_FORMAT", (result as DecryptionResult.Error).message)
    }

    // ==================== UNIQUENESS ====================

    @Test
    fun `encrypting same data twice produces different ciphertexts`() = runBlocking {
        val data = "deterministic?".toByteArray()
        val pass = "same".toCharArray()

        val enc1 = engine.encrypt(data, pass, ContentType.TEXT)
        val enc2 = engine.encrypt(data, "same".toCharArray(), ContentType.TEXT)

        // Different random salt + nonce should make them different
        assertFalse(enc1.contentEquals(enc2))
    }

    // ==================== SIMULATED FILE TYPES ====================

    @Test
    fun `encrypt-decrypt JPEG-like file`() = runBlocking {
        // JPEG starts with FF D8 FF
        val jpegData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                ByteArray(10_000) { (it % 256).toByte() }
        val pass = "jpeg123".toCharArray()

        val enc = engine.encrypt(jpegData, pass, ContentType.IMAGE)
        val result = engine.decrypt(enc, "jpeg123".toCharArray())

        assertTrue(result is DecryptionResult.ImageMessage)
        assertArrayEquals(jpegData, (result as DecryptionResult.ImageMessage).imageBytes)
    }

    @Test
    fun `encrypt-decrypt ZIP-like document`() = runBlocking {
        // ZIP starts with PK (50 4B)
        val fileName = "archive.zip"
        val zipContent = byteArrayOf(0x50, 0x4B, 0x03, 0x04) +
                ByteArray(50_000) { (it % 256).toByte() }

        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val docPayload = ByteArray(2 + nameBytes.size + zipContent.size)
        docPayload[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
        docPayload[1] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, docPayload, 2, nameBytes.size)
        System.arraycopy(zipContent, 0, docPayload, 2 + nameBytes.size, zipContent.size)

        val pass = "zipFile!".toCharArray()
        val enc = engine.encrypt(docPayload, pass, ContentType.DOCUMENT)
        val result = engine.decrypt(enc, "zipFile!".toCharArray())

        assertTrue(result is DecryptionResult.DocumentMessage)
        val doc = result as DecryptionResult.DocumentMessage
        assertEquals(fileName, doc.fileName)
        assertArrayEquals(zipContent, doc.documentBytes)
    }

    @Test
    fun `encrypt-decrypt PDF-like document`() = runBlocking {
        // PDF starts with %PDF
        val fileName = "document.pdf"
        val pdfContent = "%PDF-1.7 fake pdf body".toByteArray() +
                ByteArray(20_000) { (it % 256).toByte() }

        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val docPayload = ByteArray(2 + nameBytes.size + pdfContent.size)
        docPayload[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
        docPayload[1] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, docPayload, 2, nameBytes.size)
        System.arraycopy(pdfContent, 0, docPayload, 2 + nameBytes.size, pdfContent.size)

        val pass = "pdfSecure".toCharArray()
        val enc = engine.encrypt(docPayload, pass, ContentType.DOCUMENT)
        val result = engine.decrypt(enc, "pdfSecure".toCharArray())

        assertTrue(result is DecryptionResult.DocumentMessage)
        val doc = result as DecryptionResult.DocumentMessage
        assertEquals(fileName, doc.fileName)
        assertArrayEquals(pdfContent, doc.documentBytes)
    }

    @Test
    fun `encrypt-decrypt TXT document`() = runBlocking {
        val fileName = "notes.txt"
        val txtContent = "Line 1\nLine 2\nLine 3\n\u041F\u0440\u0438\u0432\u0435\u0442 \u043C\u0438\u0440!".toByteArray(Charsets.UTF_8)

        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val docPayload = ByteArray(2 + nameBytes.size + txtContent.size)
        docPayload[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
        docPayload[1] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, docPayload, 2, nameBytes.size)
        System.arraycopy(txtContent, 0, docPayload, 2 + nameBytes.size, txtContent.size)

        val pass = "txtPass".toCharArray()
        val enc = engine.encrypt(docPayload, pass, ContentType.DOCUMENT)
        val result = engine.decrypt(enc, "txtPass".toCharArray())

        assertTrue(result is DecryptionResult.DocumentMessage)
        val doc = result as DecryptionResult.DocumentMessage
        assertEquals(fileName, doc.fileName)
        assertArrayEquals(txtContent, doc.documentBytes)
    }

    @Test
    fun `encrypt-decrypt MP3-like document`() = runBlocking {
        // MP3 starts with ID3 or FF FB
        val fileName = "song.mp3"
        val mp3Content = byteArrayOf(0x49, 0x44, 0x33) + // "ID3"
                ByteArray(100_000) { (it % 256).toByte() }

        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val docPayload = ByteArray(2 + nameBytes.size + mp3Content.size)
        docPayload[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
        docPayload[1] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, docPayload, 2, nameBytes.size)
        System.arraycopy(mp3Content, 0, docPayload, 2 + nameBytes.size, mp3Content.size)

        val pass = "musicFile".toCharArray()
        val enc = engine.encrypt(docPayload, pass, ContentType.DOCUMENT)
        val result = engine.decrypt(enc, "musicFile".toCharArray())

        assertTrue(result is DecryptionResult.DocumentMessage)
        val doc = result as DecryptionResult.DocumentMessage
        assertEquals(fileName, doc.fileName)
        assertArrayEquals(mp3Content, doc.documentBytes)
    }

    // ==================== TRANSPORT DAMAGE (found on real phones 2026-09-13) ====================

    @Test
    fun `a file that arrived with a leading CR LF still decrypts`() = runBlocking {
        val original = "Встретимся в 19:00"
        val encrypted = engine.encrypt(
            original.toByteArray(Charsets.UTF_8), "ключ-1".toCharArray(), ContentType.TEXT
        )
        val mangled = byteArrayOf(0x0D, 0x0A) + encrypted

        val result = engine.decrypt(mangled, "ключ-1".toCharArray())

        assertTrue(result is DecryptionResult.TextMessage)
        assertEquals(original, (result as DecryptionResult.TextMessage).plaintext)
    }

    @Test
    fun `a file that arrived with a trailing CR LF still decrypts`() = runBlocking {
        val original = "photo caption"
        val encrypted = engine.encrypt(
            original.toByteArray(Charsets.UTF_8), "ключ-2".toCharArray(), ContentType.TEXT
        )
        val mangled = encrypted + byteArrayOf(0x0D, 0x0A)

        val result = engine.decrypt(mangled, "ключ-2".toCharArray())

        assertTrue(result is DecryptionResult.TextMessage)
        assertEquals(original, (result as DecryptionResult.TextMessage).plaintext)
    }

    @Test
    fun `noise on both ends at once still decrypts`() = runBlocking {
        val original = "both ends"
        val encrypted = engine.encrypt(
            original.toByteArray(Charsets.UTF_8), "ключ-3".toCharArray(), ContentType.TEXT
        )
        val mangled = byteArrayOf(0x0A) + encrypted + byteArrayOf(0x0A)

        val result = engine.decrypt(mangled, "ключ-3".toCharArray())

        assertTrue(result is DecryptionResult.TextMessage)
        assertEquals(original, (result as DecryptionResult.TextMessage).plaintext)
    }

    @Test
    fun `a wrong passphrase is still reported as wrong and not retried away`() = runBlocking {
        val encrypted = engine.encrypt(
            "secret".toByteArray(Charsets.UTF_8), "правильный".toCharArray(), ContentType.TEXT
        )

        val result = engine.decrypt(encrypted, "неправильный".toCharArray())

        assertTrue(result is DecryptionResult.Error)
        assertEquals("BAD_PASSPHRASE", (result as DecryptionResult.Error).message)
    }

    @Test
    fun `a damaged non-whitespace tail is not silently accepted`() = runBlocking {
        val encrypted = engine.encrypt(
            "secret".toByteArray(Charsets.UTF_8), "ключ-4".toCharArray(), ContentType.TEXT
        )
        val damaged = encrypted + byteArrayOf(0x41)

        val result = engine.decrypt(damaged, "ключ-4".toCharArray())

        assertTrue(result is DecryptionResult.Error)
    }

    @Test
    fun `more trailing noise than allowed is rejected`() = runBlocking {
        val encrypted = engine.encrypt(
            "secret".toByteArray(Charsets.UTF_8), "ключ-5".toCharArray(), ContentType.TEXT
        )
        val mangled = encrypted + ByteArray(5) { 0x0A }

        val result = engine.decrypt(mangled, "ключ-5".toCharArray())

        assertTrue(result is DecryptionResult.Error)
    }
}
