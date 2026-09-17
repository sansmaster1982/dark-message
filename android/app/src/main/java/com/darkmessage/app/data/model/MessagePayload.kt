package com.darkmessage.app.data.model

data class MessagePayload(
    val version: PayloadVersion,
    val contentType: ContentType,
    val salt: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessagePayload) return false
        return version == other.version &&
                contentType == other.contentType &&
                salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}

enum class PayloadVersion(val code: Byte) {
    V1(0x01);

    companion object {
        fun fromCode(code: Byte): PayloadVersion? = entries.find { it.code == code }
    }
}

enum class ContentType(val code: Byte) {
    TEXT(0x01),
    IMAGE(0x02),
    DOCUMENT(0x03);

    companion object {
        fun fromCode(code: Byte): ContentType? = entries.find { it.code == code }
    }
}
