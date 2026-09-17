package com.darkmessage.app.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name a received document ends up with decides whether the system will open it at all.
 *
 * These cases come from the owner's own phone: real file names carrying dates, version numbers
 * and brackets after a dot. "The name has a dot in it" used to count as an extension, so a file
 * called "КП 14.09.2026" was written as-is and nothing would open it.
 *
 * Every expectation here is mirrored by an iOS test over the same names, because the two apps
 * have to name the same file the same way.
 */
class DocumentFileNameTest {

    // MARK: sample content with real headers

    private val pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>"
        .toByteArray(Charsets.US_ASCII)

    private fun officeZip(entry: String): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(26) +
            entry.toByteArray(Charsets.US_ASCII) + ByteArray(256)

    /** A legacy Office container: the header plus the stream name that identifies the flavour. */
    private fun oleDocument(streamName: String): ByteArray {
        val header = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )
        val wide = ByteArray(streamName.length * 2)
        streamName.forEachIndexed { index, character ->
            wide[index * 2] = (character.code and 0xFF).toByte()
            wide[index * 2 + 1] = (character.code shr 8).toByte()
        }
        return header + ByteArray(504) + wide + ByteArray(64)
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
        ByteArray(64)

    private val plainText = "Обычный текст без заголовка".toByteArray(Charsets.UTF_8)

    // MARK: names that already carry a real extension

    @Test
    fun `real names from the owner's phone keep exactly the name they came with`() {
        val names = listOf(
            "1 Ф9И7 (1).pdf",
            "+КП_ Пушкинская 67_  КОНД 14.09.2026.pdf",
            "Хронология Ведучи КП 2025-2026 (1).docx",
            "ЗП офис СМР 2026г..xlsx",
            "Лист Microsoft Excel(1).xlsx",
            "Таганрог (ридан, труба, изоляция, ППР).xlsx",
            "2_5233187358324593625(1).xls",
            "Страницы_из_Раздел_ПД_5_Подраздел_4_Часть_1_ИОС4_1_3.pdf"
        )
        for (name in names) {
            assertEquals("the sanitiser changed a perfectly good name", name, sanitizeFileName(name))
            assertTrue("$name should count as having an extension", hasUsableExtension(name))
            assertEquals(name, documentFileName(name, pdf))
        }
    }

    // MARK: the bug - a date after a dot is not an extension

    @Test
    fun `a date at the end of the name is not mistaken for an extension`() {
        assertFalse(hasUsableExtension("КП 14.09.2026"))
        assertFalse(hasUsableExtension("отчёт за 2026г."))
        assertFalse(hasUsableExtension("Смета v1.2"))
        assertFalse(hasUsableExtension("документ"))
    }

    @Test
    fun `a name whose extension was lost gets one back from the content`() {
        assertEquals("КП 14.09.2026.pdf", documentFileName("КП 14.09.2026", pdf))
        assertEquals(
            "Хронология 2025-2026.docx",
            documentFileName("Хронология 2025-2026", officeZip("word/document.xml"))
        )
        assertEquals("смета.xlsx", documentFileName("смета", officeZip("xl/workbook.xml")))
        assertEquals("снимок.jpg", documentFileName("снимок", jpeg))
    }

    @Test
    fun `a pdf with stray bytes glued to the front is still recognised`() {
        // A real sample from the field: a PDF sent Android to iPhone through Dark Message,
        // arriving with a CR LF glued to the front and no extension. The bytes are a genuine,
        // complete PDF - proper header, proper %%EOF - so nothing in the crypto or framing
        // damaged it. Only the offset-0 magic-byte check missed it, which reproduced "the
        // extension is gone" on a file that was never actually broken. ISO 32000-1 §7.5.2
        // itself allows this: conforming readers scan the first 1024 bytes for "%PDF" rather
        // than requiring byte 0. Kept in step with iOS.
        val withCrLf = byteArrayOf(0x0D, 0x0A) + "%PDF-1.3".toByteArray(Charsets.US_ASCII) +
            ByteArray(40)
        assertEquals("pdf", guessedExtension(withCrLf))

        // The allowance has a limit: junk far past where any real tool would place the header
        // must not be treated as a PDF that merely arrived stretched.
        val tooFar = ByteArray(1024) + "%PDF-1.3".toByteArray(Charsets.US_ASCII)
        assertNull(guessedExtension(tooFar))
    }

    @Test
    fun `legacy office documents are recognised too`() {
        assertEquals("doc", guessedExtension(oleDocument("WordDocument")))
        assertEquals("xls", guessedExtension(oleDocument("Workbook")))
        assertEquals("ppt", guessedExtension(oleDocument("PowerPoint Document")))
        assertEquals("отчёт 2026г.xls", documentFileName("отчёт 2026г", oleDocument("Workbook")))
    }

    @Test
    fun `content that cannot be identified leaves the name alone`() {
        assertNull(guessedExtension(plainText))
        assertNull(guessedExtension(ByteArray(0)))
        assertNull(guessedExtension(byteArrayOf(0x50, 0x4B)))
        assertEquals("записка", documentFileName("записка", plainText))
    }

    @Test
    fun `an extension the sender gave is never second-guessed`() {
        // A PDF named .txt stays .txt: the sender named it, and inventing a different
        // extension would be a lie about someone else's file.
        assertEquals("странный.txt", documentFileName("странный.txt", pdf))
    }

    // MARK: names that are dangerous or too long

    @Test
    fun `a path in the name never escapes the directory`() {
        assertEquals("chats.json", sanitizeFileName("../../databases/chats.json"))
        assertEquals("passwd", sanitizeFileName("/etc/passwd"))
        assertEquals("hidden.pdf", sanitizeFileName(".hidden.pdf"))
        assertEquals("document", sanitizeFileName(null))
        assertEquals("document", sanitizeFileName("   "))
    }

    @Test
    fun `a very long name is shortened in the middle so the extension survives`() {
        val long = "очень_длинное_имя_".repeat(12) + ".pdf"
        assertTrue("the sample must exceed the limit", long.length > 120)

        val safe = sanitizeFileName(long)
        assertEquals(120, safe.length)
        assertTrue("the extension was cut off: $safe", safe.endsWith(".pdf"))
        assertTrue(hasUsableExtension(safe))
    }

    @Test
    fun `a long name with no extension is still capped`() {
        val long = "имя".repeat(100)
        assertEquals(120, sanitizeFileName(long).length)
    }
}
