package com.somecatcode.ebookreader.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.somecatcode.ebookreader.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The translated resource sets resolve for their locale (and keep the format placeholders). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocalizationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "en")
    fun englishIsTheDefault() {
        assertEquals("Library", context.getString(R.string.nav_library))
        assertEquals("Smart shelf", context.getString(R.string.shelf_smart))
    }

    @Test
    @Config(qualifiers = "es")
    fun spanishResolves() {
        val res = context.resources
        assertEquals("Biblioteca", res.getString(R.string.nav_library))
        assertEquals("Estantería inteligente", res.getString(R.string.shelf_smart))
        assertEquals("Progreso de lectura", res.getString(R.string.settings_sync))
        assertEquals("3 de 7 leídos", res.getString(R.string.series_read_count, 3, 7))
        assertEquals("1 libro", res.getQuantityString(R.plurals.books_count, 1, 1))
        assertEquals("5 libros", res.getQuantityString(R.plurals.books_count, 5, 5))
        assertEquals("Tamaño de letra: 120 %", res.getString(R.string.settings_reader_font_size, 120))
    }

    @Test
    @Config(qualifiers = "ja")
    fun japaneseResolves() {
        val res = context.resources
        assertEquals("ライブラリ", res.getString(R.string.nav_library))
        assertEquals("スマート本棚", res.getString(R.string.shelf_smart))
        assertEquals("読書の進捗", res.getString(R.string.settings_sync))
        assertEquals("1 冊", res.getQuantityString(R.plurals.books_count, 1, 1))
        assertEquals("5 冊", res.getQuantityString(R.plurals.books_count, 5, 5))
        assertEquals("同期とダウンロード", res.getString(R.string.data_channel_name))
    }
}
