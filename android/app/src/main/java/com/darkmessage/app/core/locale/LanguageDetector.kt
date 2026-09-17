package com.darkmessage.app.core.locale

import android.content.Context
import android.content.res.Resources
import android.telephony.TelephonyManager
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import com.darkmessage.app.ui.screens.settings.AppLanguage
import java.util.Locale

/**
 * Picks the app language for a FIRST launch, i.e. only when the user has not chosen one yet.
 *
 * It reads the TRUE system locale list, never the per-app locale override. This matters because
 * `AppCompatDelegate.setApplicationLocales()` (called from MainActivity) makes `Locale.getDefault()`,
 * `LocaleListCompat.getDefault()/getAdjustedDefault()` and `context.resources.configuration.locales`
 * all report the app override - on Android 13+ that override is even persisted by the OS across
 * launches, so an earlier "en" write would otherwise be echoed back forever (see AND-LANG-1).
 *
 * Rule (see [decide]): RUSSIAN if any system locale has language "ru", OR the primary (first)
 * system locale's country is "RU", OR the SIM / current mobile network country is "ru";
 * otherwise ENGLISH. Reading the SIM/network country needs no permission.
 */
object LanguageDetector {

    private const val RU_LANGUAGE = "ru"   // ISO 639-1, as returned by Locale.getLanguage()
    private const val RU_COUNTRY = "RU"    // ISO 3166-1 alpha-2, as returned by Locale.getCountry()
    private const val RU_ISO_LOWER = "ru"  // TelephonyManager returns lowercase ISO 3166-1 alpha-2

    /**
     * Call ONLY when no user preference is stored. Cheap (a few binder calls) and never throws:
     * every system lookup is guarded, and missing telephony (tablets, emulators) simply means
     * the SIM/network hints are absent.
     */
    fun detect(context: Context): AppLanguage {
        val app: Context = context.applicationContext ?: context
        val locales = systemLocales(app)

        val telephony = telephonyManager(app)
        val simCountry: String? = telephony?.let { tm ->
            runCatching { tm.simCountryIso }.getOrNull()
        }
        // networkCountryIso is documented as unreliable on CDMA networks, so skip it there.
        val networkCountry: String? = telephony?.let { tm ->
            val isCdma = runCatching { tm.phoneType == TelephonyManager.PHONE_TYPE_CDMA }.getOrDefault(true)
            if (isCdma) null else runCatching { tm.networkCountryIso }.getOrNull()
        }

        return decide(locales, simCountry, networkCountry)
    }

    /**
     * Pure decision rule (no Android dependencies, unit-testable):
     * - any locale with language "ru"            -> RUSSIAN  (ru_RU, ru_UA, en_US + ru_RU, ...)
     * - first locale's country is "RU"          -> RUSSIAN  (en_RU, tt_RU, ba_RU, os_RU, ...)
     * - SIM country or network country is "ru"  -> RUSSIAN  (device in Russia, English UI)
     * - otherwise                               -> ENGLISH
     */
    fun decide(
        locales: List<Locale>,
        simCountryIso: String?,
        networkCountryIso: String?
    ): AppLanguage {
        val anyRussianLanguage = locales.any { locale ->
            (locale.language ?: "").equals(RU_LANGUAGE, ignoreCase = true)
        }
        val primaryCountry: String = locales.firstOrNull()?.country ?: ""
        val primaryRegionIsRussia = primaryCountry.equals(RU_COUNTRY, ignoreCase = true)
        val simIsRussia = (simCountryIso ?: "").equals(RU_ISO_LOWER, ignoreCase = true)
        val networkIsRussia = (networkCountryIso ?: "").equals(RU_ISO_LOWER, ignoreCase = true)

        val isRussian = anyRussianLanguage || primaryRegionIsRussia || simIsRussia || networkIsRussia
        return if (isRussian) AppLanguage.RUSSIAN else AppLanguage.ENGLISH
    }

    /**
     * The system (device) locale list, unaffected by per-app overrides.
     * API 33+: LocaleManager.getSystemLocales(); below: Resources.getSystem().configuration.locales
     * (both via LocaleManagerCompat). Falls back to Resources.getSystem() directly if that fails.
     */
    private fun systemLocales(context: Context): List<Locale> {
        val fromManager: LocaleListCompat? =
            runCatching { LocaleManagerCompat.getSystemLocales(context) }.getOrNull()

        val list: LocaleListCompat = if (fromManager != null && !fromManager.isEmpty) {
            fromManager
        } else {
            runCatching { LocaleListCompat.wrap(Resources.getSystem().configuration.locales) }
                .getOrDefault(LocaleListCompat.getEmptyLocaleList())
        }

        val result = ArrayList<Locale>(list.size())
        for (index in 0 until list.size()) {
            val locale: Locale? = list.get(index)
            if (locale != null) {
                result.add(locale)
            }
        }
        return result
    }

    private fun telephonyManager(context: Context): TelephonyManager? =
        runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
}
