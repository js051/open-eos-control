package dev.openeos.control.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(
    val languageTag: String,
) {
    SYSTEM(""),
    ENGLISH("en"),
    TRADITIONAL_CHINESE("zh-TW"),
}

object AppLanguageManager {
    fun current(): AppLanguage = appLanguageForTag(
        AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag().orEmpty(),
    )

    fun set(language: AppLanguage) {
        val locales = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.languageTag)
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

internal fun appLanguageForTag(tag: String): AppLanguage = when {
    tag.equals(AppLanguage.ENGLISH.languageTag, ignoreCase = true) ||
        tag.startsWith("en-", ignoreCase = true) -> AppLanguage.ENGLISH
    tag.equals(AppLanguage.TRADITIONAL_CHINESE.languageTag, ignoreCase = true) ||
        tag.startsWith("zh-Hant", ignoreCase = true) -> AppLanguage.TRADITIONAL_CHINESE
    else -> AppLanguage.SYSTEM
}
