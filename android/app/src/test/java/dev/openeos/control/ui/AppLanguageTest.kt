package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun emptyLocaleUsesSystemLanguage() {
        assertEquals(AppLanguage.SYSTEM, appLanguageForTag(""))
    }

    @Test
    fun englishLocaleVariantsUseEnglish() {
        assertEquals(AppLanguage.ENGLISH, appLanguageForTag("en"))
        assertEquals(AppLanguage.ENGLISH, appLanguageForTag("en-US"))
    }

    @Test
    fun traditionalChineseLocaleVariantsUseTraditionalChinese() {
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, appLanguageForTag("zh-TW"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, appLanguageForTag("zh-Hant-TW"))
    }
}
