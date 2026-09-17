package com.darkmessage.app.ui.screens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmessage.app.core.locale.LanguageDetector
import com.darkmessage.app.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings"
)

private val THEME_KEY = stringPreferencesKey("theme_mode")
private val LANGUAGE_KEY = stringPreferencesKey("language")                // "en" | "ru"
private val LANGUAGE_SOURCE_KEY = stringPreferencesKey("language_source")  // "auto" | "user"
private val SETTINGS_SCHEMA_KEY = intPreferencesKey("settings_schema")     // see SETTINGS_SCHEMA_VERSION
private val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")
private val TUTORIAL_COMPLETED_KEY = booleanPreferencesKey("tutorial_completed")
private val QR_SENDER_NAME_KEY = stringPreferencesKey("qr_sender_name")   // §3.1/§3.2 sender display name

/**
 * Settings schema version stored in DataStore.
 * 1 (implicit, key absent) = legacy: LANGUAGE_KEY written only by an explicit tap in Settings.
 * 2 = language is decided ONCE on first launch and persisted; LANGUAGE_SOURCE_KEY says whether it
 *     came from auto-detection ("auto") or from the user ("user"), for future migrations.
 */
private const val SETTINGS_SCHEMA_VERSION = 2
private const val LANGUAGE_SOURCE_AUTO = "auto"
private const val LANGUAGE_SOURCE_USER = "user"

enum class AppLanguage(val code: String) {
    ENGLISH("en"),
    RUSSIAN("ru")
}

private fun languageFromCode(code: String?): AppLanguage? = when (code) {
    AppLanguage.RUSSIAN.code -> AppLanguage.RUSSIAN
    AppLanguage.ENGLISH.code -> AppLanguage.ENGLISH
    else -> null
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // Placeholder until DataStore has loaded. Consumers MUST gate on [isLoaded] before acting on
    // it (MainActivity only calls setApplicationLocales once isLoaded is true), so this value is
    // never pushed to the OS and never influences the first-launch decision.
    private val _language = MutableStateFlow(AppLanguage.ENGLISH)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _onboardingCompleted = MutableStateFlow(false)
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    private val _tutorialCompleted = MutableStateFlow(false)
    val tutorialCompleted: StateFlow<Boolean> = _tutorialCompleted.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /** Display name put into the chat-key QR payload (spec section 3.1/3.2). Empty = not set. */
    private val _qrSenderName = MutableStateFlow("")
    val qrSenderName: StateFlow<String> = _qrSenderName.asStateFlow()

    init {
        viewModelScope.launch {
            // Decide the language exactly once (first launch) BEFORE exposing settings, so the
            // very first isLoaded=true emission already carries the persisted decision.
            val autoDetected: AppLanguage? = decideLanguageOnce()

            context.settingsDataStore.data.collect { prefs ->
                _themeMode.value = when (prefs[THEME_KEY]) {
                    "LIGHT" -> ThemeMode.LIGHT
                    "SYSTEM" -> ThemeMode.SYSTEM
                    else -> ThemeMode.DARK
                }
                // Never re-derive from the system here: the saved value is the source of truth.
                // autoDetected is only used if persisting failed (IOException) in this session.
                _language.value = languageFromCode(prefs[LANGUAGE_KEY])
                    ?: autoDetected
                    ?: AppLanguage.ENGLISH
                _onboardingCompleted.value = prefs[ONBOARDING_COMPLETED_KEY] ?: false
                _tutorialCompleted.value = prefs[TUTORIAL_COMPLETED_KEY] ?: false
                _qrSenderName.value = prefs[QR_SENDER_NAME_KEY] ?: ""
                _isLoaded.value = true
            }
        }
    }

    /**
     * First-launch language decision, made exactly once per install and persisted.
     *
     * - No saved LANGUAGE_KEY: detect from the TRUE system locales / SIM / network
     *   ([LanguageDetector], which ignores the per-app locale override) and persist it with
     *   language_source="auto".
     * - Saved LANGUAGE_KEY but legacy schema: it could only have been written by an explicit tap
     *   in Settings, so stamp language_source="user" (existing choice is never reset).
     * - Otherwise: nothing to do.
     *
     * The checks are repeated inside `edit {}` because DataStore serialises edits and two
     * SettingsViewModel instances exist at runtime (Activity-scoped and NavBackStackEntry-scoped);
     * whichever commits first wins and the other becomes a no-op.
     *
     * @return the detected language when a fresh decision was made (so the caller can keep it in
     *         memory if the write failed), or null when a saved value already existed.
     */
    private suspend fun decideLanguageOnce(): AppLanguage? {
        val prefs = context.settingsDataStore.data.first()
        val hasSavedLanguage = languageFromCode(prefs[LANGUAGE_KEY]) != null
        val schemaIsCurrent = (prefs[SETTINGS_SCHEMA_KEY] ?: 1) >= SETTINGS_SCHEMA_VERSION
        if (hasSavedLanguage && schemaIsCurrent) {
            return null
        }

        val detected: AppLanguage = withContext(Dispatchers.IO) {
            LanguageDetector.detect(context)
        }

        try {
            context.settingsDataStore.edit { p ->
                if (languageFromCode(p[LANGUAGE_KEY]) == null) {
                    p[LANGUAGE_KEY] = detected.code
                    p[LANGUAGE_SOURCE_KEY] = LANGUAGE_SOURCE_AUTO
                } else if (p[LANGUAGE_SOURCE_KEY] == null) {
                    p[LANGUAGE_SOURCE_KEY] = LANGUAGE_SOURCE_USER
                }
                p[SETTINGS_SCHEMA_KEY] = SETTINGS_SCHEMA_VERSION
            }
        } catch (e: IOException) {
            // Disk write failed: keep the decision in memory for this session; it will be
            // re-attempted (and persisted) on the next launch. Do not block the UI over this.
        }

        return if (hasSavedLanguage) null else detected
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[THEME_KEY] = mode.name
            }
        }
    }

    /** Explicit user choice from Settings. Always wins over the auto-detected value. */
    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[LANGUAGE_KEY] = lang.code
                prefs[LANGUAGE_SOURCE_KEY] = LANGUAGE_SOURCE_USER
                prefs[SETTINGS_SCHEMA_KEY] = SETTINGS_SCHEMA_VERSION
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[ONBOARDING_COMPLETED_KEY] = true
            }
        }
    }

    /** Remembers the sender display name offered by the QR sender screen. */
    fun setQrSenderName(name: String) {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[QR_SENDER_NAME_KEY] = name
            }
        }
    }

    fun completeTutorial() {
        viewModelScope.launch {
            context.settingsDataStore.edit { prefs ->
                prefs[TUTORIAL_COMPLETED_KEY] = true
            }
        }
    }
}
