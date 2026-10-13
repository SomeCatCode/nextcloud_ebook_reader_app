package com.somecatcode.ebookreader.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun everyOptionRoundTripsThroughItsTag() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }

    @Test
    fun offersSystemDefaultAndEightLanguages() {
        assertEquals(9, AppLanguage.entries.size)
        assertEquals(listOf("de", "en", "es", "fr", "ja", "ar", "tr", "fa-AF").toSet(), AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.map { it.tag }.toSet())
        assertTrue(AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.all { !it.nativeName.isNullOrBlank() })
    }

    @Test
    fun blankOrUnknownMeansSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("xx"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("fa"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("fa-IR"))
    }

    @Test
    fun regionalTagsMapToTheLanguage() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de-AT"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromTag("fr_CA"))
        assertEquals(AppLanguage.DARI, AppLanguage.fromTag("fa-af"))
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromTag("ar-EG"))
    }
}
