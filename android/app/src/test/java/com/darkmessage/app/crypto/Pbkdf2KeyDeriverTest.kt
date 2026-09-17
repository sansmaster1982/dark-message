package com.darkmessage.app.crypto

import com.darkmessage.app.core.constants.CryptoConstants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Pbkdf2KeyDeriverTest {

    private val deriver = Pbkdf2KeyDeriver()

    @Test
    fun `deriveKey returns 32-byte key`() = runBlocking {
        val passphrase = "testPassword123".toCharArray()
        val salt = deriver.generateSalt()

        val key = deriver.deriveKey(passphrase, salt)
        assertEquals(32, key.size) // 256 bits
    }

    @Test
    fun `same passphrase and salt produces same key`() = runBlocking {
        val passphrase = "stableKey!".toCharArray()
        val salt = ByteArray(16) { 0x42 }

        val key1 = deriver.deriveKey(passphrase, salt)
        val key2 = deriver.deriveKey("stableKey!".toCharArray(), salt)

        assertArrayEquals(key1, key2)
    }

    @Test
    fun `different passphrases produce different keys`() = runBlocking {
        val salt = ByteArray(16) { 0x42 }

        val key1 = deriver.deriveKey("password1".toCharArray(), salt)
        val key2 = deriver.deriveKey("password2".toCharArray(), salt)

        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `different salts produce different keys`() = runBlocking {
        val passphrase = "samePassword".toCharArray()
        val salt1 = ByteArray(16) { 0x01 }
        val salt2 = ByteArray(16) { 0x02 }

        val key1 = deriver.deriveKey(passphrase, salt1)
        val key2 = deriver.deriveKey("samePassword".toCharArray(), salt2)

        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `generateSalt returns 16 bytes`() {
        val salt = deriver.generateSalt()
        assertEquals(CryptoConstants.SALT_LENGTH, salt.size)
    }

    @Test
    fun `generateSalt produces unique salts`() {
        val salts = (1..10).map { deriver.generateSalt() }
        // All salts should be different
        for (i in salts.indices) {
            for (j in i + 1 until salts.size) {
                assertFalse(
                    "Salt $i and $j should differ",
                    salts[i].contentEquals(salts[j])
                )
            }
        }
    }

    @Test
    fun `empty passphrase still derives a key`() = runBlocking {
        val key = deriver.deriveKey(charArrayOf(), ByteArray(16))
        assertEquals(32, key.size)
    }

    @Test
    fun `unicode passphrase works`() = runBlocking {
        val passphrase = "\u041F\u0440\u0438\u0432\u0435\u0442\uD83D\uDD12".toCharArray()
        val salt = deriver.generateSalt()
        val key = deriver.deriveKey(passphrase, salt)
        assertEquals(32, key.size)
    }
}
