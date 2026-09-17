package com.darkmessage.app.data.security

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.darkmessage.app.core.constants.AppConstants
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppConstants.DATASTORE_NAME
)

@Singleton
class TinkSecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) : SecureStorage {

    private val aead: Aead by lazy {
        val keysetHandle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, AppConstants.TINK_KEYSET_NAME, AppConstants.TINK_PREF_FILE)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(AppConstants.TINK_MASTER_KEY_URI)
            .build()
            .keysetHandle
        keysetHandle.getPrimitive(Aead::class.java)
    }

    override suspend fun storePassphrase(chatId: Long, passphrase: String) {
        val encrypted = aead.encrypt(
            passphrase.toByteArray(Charsets.UTF_8),
            "chat_$chatId".toByteArray(Charsets.UTF_8)
        )
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("passphrase_$chatId")] = encoded
        }
    }

    /**
     * Returns null instead of throwing when the stored value cannot be read.
     *
     * The declared type is `String?` and every caller already handles null, but the body
     * used to let three different failures escape: a corrupted Base64 string, an AEAD tag
     * that no longer verifies, and - the realistic one - a Keystore master key invalidated
     * by a device change or a lock-screen reset, which makes the lazy `aead` itself throw.
     * Since the chat list now derives a key fingerprint for every chat, that exception
     * would surface while merely opening the app. A chat whose key cannot be read shows up
     * without a fingerprint and refuses to encrypt, which is recoverable; a crash is not.
     */
    override suspend fun getPassphrase(chatId: Long): String? {
        val prefs = context.dataStore.data.first()
        val encoded = prefs[stringPreferencesKey("passphrase_$chatId")] ?: return null
        return runCatching {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            val decrypted = aead.decrypt(
                encrypted,
                "chat_$chatId".toByteArray(Charsets.UTF_8)
            )
            String(decrypted, Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun deletePassphrase(chatId: Long) {
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("passphrase_$chatId"))
        }
    }
}
