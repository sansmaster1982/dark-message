package com.darkmessage.app.data.model

sealed class EncryptionResult {
    data class Success(
        val base64Text: String? = null,
        val rawBytes: ByteArray? = null
    ) : EncryptionResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return base64Text == other.base64Text &&
                    rawBytes.contentEquals(other.rawBytes)
        }

        override fun hashCode(): Int {
            var result = base64Text?.hashCode() ?: 0
            result = 31 * result + (rawBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    data class Error(val message: String) : EncryptionResult()
}
