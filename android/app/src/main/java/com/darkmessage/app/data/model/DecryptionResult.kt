package com.darkmessage.app.data.model

sealed class DecryptionResult {
    data class TextMessage(val plaintext: String) : DecryptionResult()

    data class ImageMessage(val imageBytes: ByteArray) : DecryptionResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageMessage) return false
            return imageBytes.contentEquals(other.imageBytes)
        }

        override fun hashCode(): Int = imageBytes.contentHashCode()
    }

    data class DocumentMessage(
        val documentBytes: ByteArray,
        val fileName: String = "document"
    ) : DecryptionResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DocumentMessage) return false
            return documentBytes.contentEquals(other.documentBytes) && fileName == other.fileName
        }

        override fun hashCode(): Int {
            var result = documentBytes.contentHashCode()
            result = 31 * result + fileName.hashCode()
            return result
        }
    }

    data class Error(val message: String) : DecryptionResult()
}
