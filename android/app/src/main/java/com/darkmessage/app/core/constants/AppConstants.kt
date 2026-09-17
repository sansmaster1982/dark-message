package com.darkmessage.app.core.constants

object AppConstants {
    const val DATABASE_NAME = "dark_message_db"
    const val DATASTORE_NAME = "dark_message_prefs"
    const val TINK_KEYSET_NAME = "dark_message_keyset"
    const val TINK_PREF_FILE = "dark_message_tink_prefs"
    const val TINK_MASTER_KEY_URI = "android-keystore://dark_message_master_key"

    // Max size for Base64 clipboard (50 KB plaintext → ~67 KB Base64)
    const val MAX_CLIPBOARD_PLAINTEXT_SIZE = 50 * 1024

    const val EXPORT_FILE_NAME = "dark_message_backup.json"
    const val EXPORT_VERSION = 1
}
