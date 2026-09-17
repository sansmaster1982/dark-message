package com.darkmessage.app.core.constants

object CryptoConstants {
    // PBKDF2-HMAC-SHA512 parameters (must match iOS KeyDeriver for cross-platform decryption)
    const val PBKDF2_ITERATIONS = 600_000
    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA512"
    const val PBKDF2_KEY_LENGTH = 256         // bits

    // AES-GCM parameters
    const val AES_KEY_SIZE = 256              // bits
    const val GCM_NONCE_LENGTH = 12           // bytes (NIST recommended)
    const val GCM_TAG_LENGTH = 128            // bits

    // Payload structure sizes
    const val SALT_LENGTH = 16                // bytes
    const val VERSION_LENGTH = 1              // byte
    const val CONTENT_TYPE_LENGTH = 1         // byte

    // Cipher transformation
    const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

    // File extension
    const val DARKM_EXTENSION = ".darkm"
    const val DARKM_MIME_TYPE = "application/octet-stream"

    // Minimum payload size: version + contentType + salt + nonce + GCM tag (16 bytes)
    const val MIN_PAYLOAD_SIZE = VERSION_LENGTH + CONTENT_TYPE_LENGTH + SALT_LENGTH + GCM_NONCE_LENGTH + 16
}
