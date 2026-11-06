package com.somecatcode.ebookreader.ui.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Languages selectable in the app settings. [tag] is the BCP 47 tag handed to the per-app language API
 * (empty = follow the system language); [nativeName] is shown in the language itself and is never translated.
 */
enum class AppLanguage(val tag: String, val nativeName: String?) {
    SYSTEM("", null),
    GERMAN("de", "Deutsch"),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE_BRAZIL("pt-BR", "Português (Brasil)"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    CHINESE_SIMPLIFIED("zh-CN", "简体中文"),
    ARABIC("ar", "العربية"),
    TURKISH("tr", "Türkçe"),
    // Dari (Afghan Persian). Android has no resource qualifier for ISO 639-3 "prs", so fa-AF is used.
    DARI("fa-AF", "دری");

    companion object {
        /** Option for a stored language tag; unknown or blank tags mean [SYSTEM]. */
        fun fromTag(tag: String?): AppLanguage {
            val t = tag?.trim().orEmpty().replace('_', '-')
            if (t.isEmpty()) return SYSTEM
            entries.firstOrNull { it.tag.equals(t, ignoreCase = true) }?.let { return it }
            val language = t.substringBefore('-')
            // Traditional Chinese (Taiwan, Hong Kong, Macau, "Hant") is not Simplified Chinese.
            if (language.equals("zh", ignoreCase = true)) return if (isSimplifiedChinese(t)) CHINESE_SIMPLIFIED else SYSTEM
            // Persian without a matching region is not Dari: it falls back to the system language.
            return entries.firstOrNull { it != SYSTEM && it != DARI && it.tag.substringBefore('-').equals(language, ignoreCase = true) } ?: SYSTEM
        }

        /** Whether a `zh` tag means Simplified Chinese: script Hans, region CN or SG, or no region at all. */
        internal fun isSimplifiedChinese(tag: String): Boolean {
            val parts = tag.split('-').drop(1).map { it.uppercase() }
            return "HANT" !in parts && parts.none { it in setOf("TW", "HK", "MO") }
        }

        /** Language currently applied through the per-app language API. */
        fun current(): AppLanguage = fromTag(AppCompatDelegate.getApplicationLocales().toLanguageTags())

        /** Applies [language] app-wide; the activity is recreated by the framework. SYSTEM clears the override. */
        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
        }
    }
}
