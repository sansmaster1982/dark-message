package com.darkmessage.app.core.files

/**
 * How a document file name is made safe, kept and - when it arrives damaged - repaired.
 *
 * Both screens use this: the Encrypt screen to decide what name to put INTO a payload, the
 * Decrypt screen to decide what a received document is called. iOS keeps the identical rules in
 * DecryptView (knownExtensions, guessedExtension, sanitizedFileName, documentFileName); the two
 * apps have to name the same file the same way.
 */

private const val DEFAULT_DOCUMENT_NAME = "document"
private const val MAX_FILE_NAME_LENGTH = 120
private val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

/**
 * Makes a sender-provided file name safe to use as a single path segment: keeps only the last
 * segment, replaces path separators / control characters, strips leading dots (".." etc.) and
 * falls back to "document" when nothing usable is left.
 */
internal fun sanitizeFileName(raw: String?): String {
    if (raw.isNullOrBlank()) return DEFAULT_DOCUMENT_NAME
    val lastSegment = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = lastSegment
        .replace(UNSAFE_FILE_NAME_CHARS, "_")
        .trim()
        .trimStart('.')
        .trim()
    if (cleaned.isBlank()) return DEFAULT_DOCUMENT_NAME
    if (cleaned.length <= MAX_FILE_NAME_LENGTH) return cleaned

    // Cutting a long name off at the limit used to take the extension with it, and a document
    // without one is exactly what neither platform can open. Shorten the middle, keep the tail.
    val extension = cleaned.substringAfterLast('.', "")
    return if (extension.isNotEmpty() && extension.length <= 8) {
        cleaned.substringBeforeLast('.').take(MAX_FILE_NAME_LENGTH - extension.length - 1) +
            "." + extension
    } else {
        cleaned.take(MAX_FILE_NAME_LENGTH)
    }
}

/**
 * Extensions the system knows how to open. A name ending in anything else - "КП 14.09.2026",
 * "отчёт за 2026г." - has no usable extension however much it looks like one, and the content
 * decides instead.
 *
 * Russian file names carry dates and version numbers after a dot all the time, so "the name has
 * a dot in it" was never a safe test. Kept identical to iOS `DecryptView.knownExtensions`.
 */
internal val KNOWN_EXTENSIONS: Set<String> = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "txt", "csv",
    "json", "xml", "html", "htm", "md", "log", "ps", "epub", "pages", "numbers",
    "key", "jpg", "jpeg", "png", "gif", "tif", "tiff", "bmp", "heic", "heif",
    "webp", "svg", "mp3", "m4a", "wav", "aac", "mp4", "mov", "m4v", "avi",
    "mkv", "zip", "rar", "7z", "gz", "tar", "darkm"
)

/** True when the name already ends in an extension the system recognises. */
internal fun hasUsableExtension(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in KNOWN_EXTENSIONS

/**
 * Extension guessed from the first bytes, for a document whose name arrived without a usable one.
 * Returns null when the content is not something we can name with confidence - a wrong extension
 * is worse than none. Kept in step with iOS `DecryptView.guessedExtension`.
 */
internal fun guessedExtension(bytes: ByteArray): String? {
    fun b(index: Int): Int = bytes[index].toInt() and 0xFF
    fun starts(vararg magic: Int, offset: Int = 0): Boolean {
        if (bytes.size < offset + magic.size) return false
        return magic.indices.all { b(offset + it) == magic[it] }
    }

    val head = bytes.copyOfRange(0, minOf(bytes.size, 8192))
    fun contains(text: String): Boolean = indexOfBytes(head, text.toByteArray(Charsets.UTF_8)) >= 0
    // The names inside a legacy Office container are UTF-16, so "Workbook" is stored with a zero
    // byte after every letter. Searching for the plain string misses it.
    fun containsWide(text: String): Boolean {
        val wide = ByteArray(text.length * 2)
        text.forEachIndexed { index, character ->
            wide[index * 2] = (character.code and 0xFF).toByte()
            wide[index * 2 + 1] = (character.code shr 8).toByte()
        }
        return indexOfBytes(head, wide) >= 0
    }

    // The PDF spec itself (ISO 32000-1 §7.5.2) does not require "%PDF" at byte 0:
    // conforming readers scan the first 1024 bytes, because some tools and some
    // transports prepend a few bytes of their own. A real sample from the field - a
    // document sent Android to iPhone through Dark Message - arrived as two stray
    // bytes (a CR LF) glued in front of an otherwise perfectly intact PDF, and an
    // offset-0 check alone missed it, exactly reproducing "the extension is gone" on
    // a file that was never actually damaged. Kept in step with iOS.
    if (indexOfBytes(bytes.copyOfRange(0, minOf(bytes.size, 1024)), byteArrayOf(0x25, 0x50, 0x44, 0x46)) >= 0) {
        return "pdf"                                                    // %PDF
    }
    if (starts(0xFF, 0xD8, 0xFF)) return "jpg"
    if (starts(0x89, 0x50, 0x4E, 0x47)) return "png"
    if (starts(0x47, 0x49, 0x46, 0x38)) return "gif"
    if (starts(0x49, 0x49, 0x2A, 0x00) || starts(0x4D, 0x4D, 0x00, 0x2A)) return "tif"
    if (starts(0x25, 0x21, 0x50, 0x53)) return "ps"                     // %!PS
    if (starts(0x7B, 0x5C, 0x72, 0x74, 0x66)) return "rtf"              // {\rtf
    if (starts(0x1F, 0x8B)) return "gz"
    if (starts(0x52, 0x61, 0x72, 0x21)) return "rar"                    // Rar!
    if (starts(0x37, 0x7A, 0xBC, 0xAF)) return "7z"
    if (starts(0x49, 0x44, 0x33)) return "mp3"                          // ID3
    if (starts(0x52, 0x49, 0x46, 0x46)) {                               // RIFF
        if (starts(0x57, 0x45, 0x42, 0x50, offset = 8)) return "webp"
        if (starts(0x57, 0x41, 0x56, 0x45, offset = 8)) return "wav"
    }
    if (starts(0x66, 0x74, 0x79, 0x70, offset = 4)) {                   // ....ftyp
        if (starts(0x68, 0x65, 0x69, offset = 8)) return "heic"
        if (starts(0x71, 0x74, offset = 8)) return "mov"
        return "mp4"
    }
    if (starts(0x50, 0x4B, 0x03, 0x04)) {
        // Every modern Office file is a zip. Look for the part that names the flavour.
        if (contains("word/")) return "docx"
        if (contains("xl/")) return "xlsx"
        if (contains("ppt/")) return "pptx"
        return "zip"
    }
    // Word 97-2003, Excel 97-2003 and the rest share one container format, so the stream names
    // inside it are what tell them apart. The owner sends .xls and .doc regularly.
    if (starts(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
        if (containsWide("WordDocument")) return "doc"
        if (containsWide("Workbook") || containsWide("Book")) return "xls"
        if (containsWide("PowerPoint")) return "ppt"
        return "doc"
    }
    return null
}

/** First index of [needle] in [haystack], or -1. */
private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > haystack.size) return -1
    outer@ for (start in 0..haystack.size - needle.size) {
        for (offset in needle.indices) {
            if (haystack[start + offset] != needle[offset]) continue@outer
        }
        return start
    }
    return -1
}

/**
 * The name a decrypted document is shown and saved under: the sender's name when it carries an
 * extension the system understands, otherwise that name with one guessed from the content.
 */
internal fun documentFileName(rawName: String?, bytes: ByteArray): String {
    val safe = sanitizeFileName(rawName)
    if (hasUsableExtension(safe)) return safe
    val guessed = guessedExtension(bytes) ?: return safe
    return sanitizeFileName("$safe.$guessed")
}
