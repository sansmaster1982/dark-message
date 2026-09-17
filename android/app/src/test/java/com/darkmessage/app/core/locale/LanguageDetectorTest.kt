package com.darkmessage.app.core.locale

import com.darkmessage.app.ui.screens.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguageDetectorTest {

    private fun decide(vararg locales: Locale, sim: String? = null, net: String? = null) =
        LanguageDetector.decide(locales.toList(), sim, net)

    @Test
    fun `russian system language gives russian`() =
        assertEquals(AppLanguage.RUSSIAN, decide(Locale("ru", "RU")))

    @Test
    fun `russian language with foreign region gives russian`() =
        assertEquals(AppLanguage.RUSSIAN, decide(Locale("ru", "UA")))

    @Test
    fun `english language in russian region gives russian`() =
        assertEquals(AppLanguage.RUSSIAN, decide(Locale("en", "RU")))

    @Test
    fun `secondary russian locale gives russian`() =
        assertEquals(AppLanguage.RUSSIAN, decide(Locale("en", "US"), Locale("ru", "RU")))

    @Test
    fun `russian sim in english device gives russian`() =
        assertEquals(AppLanguage.RUSSIAN, decide(Locale("en", "US"), sim = "ru"))

    @Test
    fun `russian network country gives russian`() =
        assertEquals(AppLanguage.RUSSIAN, decide(Locale("en", "GB"), net = "RU"))

    @Test
    fun `plain english device gives english`() =
        assertEquals(AppLanguage.ENGLISH, decide(Locale("en", "US"), sim = "us", net = "us"))

    @Test
    fun `german device gives english`() =
        assertEquals(AppLanguage.ENGLISH, decide(Locale("de", "DE")))

    @Test
    fun `no locales at all gives english`() =
        assertEquals(AppLanguage.ENGLISH, LanguageDetector.decide(emptyList(), null, null))

    @Test
    fun `secondary region RU does not count`() =
        assertEquals(AppLanguage.ENGLISH, decide(Locale("en", "US"), Locale("en", "RU")))
}
