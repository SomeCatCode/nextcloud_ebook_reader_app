package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.ui.book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeOrderTest {

    /** Ported from the server test VolumeNumberTest.php (same rules in the app). */
    @Test
    fun parsesLikeTheServer() {
        val cases = listOf(
            "Saga Vol. 10" to 10.0, "Saga vol 3" to 3.0, "Saga, Volume 12: The End" to 12.0, "Saga v02" to 2.0,
            "Saga v.7" to 7.0, "Saga Band 3" to 3.0, "Saga Tome 4" to 4.0, "Saga T04" to 4.0, "Saga #7" to 7.0,
            "Saga No. 5" to 5.0, "Saga Book 2" to 2.0, "Saga Vol. 10.5" to 10.5, "Saga Chapter 5" to 5.0,
            "Saga Ch. 12" to 12.0, "Saga Kapitel 8" to 8.0, "Saga Vol. 3 Ch. 25" to 3.0, "ワンピース 第3巻" to 3.0,
            "Saga 10" to 10.0, "Saga 10 (Digital) [Group]" to 10.0, "Saga 10.5" to 10.5, "Saga 007" to 7.0,
            "Saga 12.cbz" to 12.0, "Saga_Vol_4.epub" to 4.0, "Saga 2019" to null, "Saga" to null,
            "Catch22 Story" to null, "" to null,
        )
        cases.forEach { (text, expected) -> assertEquals(text, expected, VolumeNumbers.parse(text)) }
    }

    @Test
    fun leadingNumberOnlyForFileNames() {
        assertNull(VolumeNumbers.parse("01 - The Beginning"))
        assertEquals(1.0, VolumeNumbers.parse("01 - The Beginning", leading = true)!!, 0.0)
        assertEquals(12.0, VolumeNumbers.parse("12 Title", leading = true)!!, 0.0)
        assertNull(VolumeNumbers.parse("2001 A Space Odyssey", leading = true))
        assertNull(VolumeNumbers.parse(null))
    }

    @Test
    fun titleBeforeFileName() {
        assertEquals(10.0, VolumeNumbers.effective("S", null, "Saga 10", "/Books/Saga 2.cbz")!!, 0.0)
        assertEquals(2.0, VolumeNumbers.effective("S", null, "The Beginning", "/Books/Saga/Saga 2.cbz")!!, 0.0)
        assertEquals(3.0, VolumeNumbers.effective("S", null, null, "/Books/03 - Saga.cbz")!!, 0.0)
        assertNull(VolumeNumbers.effective("S", null, "Saga", "/Books/Saga.cbz"))
    }

    @Test
    fun withoutNumberLastAlsoDescending() {
        val books = listOf(book(1, "S extra", series = "S"), book(2, "S 2", series = "S"), book(3, "S 10", series = "S"))
        assertEquals(listOf(3L, 2L, 1L), books.sortedWith(VolumeComparatorDesc).map { it.key.fileId })
    }

    @Test
    fun metadataIndexWinsThenTitleThenFileName() {
        assertEquals(3.0, VolumeNumbers.effective("S", 3.0, "Vol 9", "/a/Vol 8.cbz")!!, 0.0)
        assertEquals(9.0, VolumeNumbers.effective("S", null, "Vol 9", "/a/Vol 8.cbz")!!, 0.0)
        assertEquals(8.0, VolumeNumbers.effective("S", null, "Plain", "/a/Vol 8.cbz")!!, 0.0)
        // no series: nothing orders the book, the index of the metadata is still kept
        assertNull(VolumeNumbers.effective(null, null, "Vol 9", "/a/b.cbz"))
        assertEquals(2.0, VolumeNumbers.effective(null, 2.0, "x", "/a/b.cbz")!!, 0.0)
    }

    @Test
    fun naturalOrderPutsTwoBeforeTen() {
        val sorted = listOf("Band 10", "Band 2", "band 1", "Band 02a").sortedWith(NaturalOrder)
        assertEquals(listOf("band 1", "Band 2", "Band 02a", "Band 10"), sorted)
    }

    @Test
    fun seriesVolumesSortNumericallyNotAlphabetically() {
        val books = listOf(
            book(1, "Saga Vol. 10", series = "Saga"),
            book(2, "Saga Vol. 2", series = "Saga"),
            book(3, "Saga Vol. 1", series = "Saga"),
            book(4, "Saga Extra", series = "Saga"),
        )
        assertEquals(listOf(3L, 2L, 1L, 4L), books.inVolumeOrder().map { it.key.fileId })
    }

    @Test
    fun metadataIndexBeatsTheTitleAndTiesFallBackToNaturalTitleThenFileId() {
        val books = listOf(
            book(1, "B 10", series = "S", seriesIndex = 1.0),
            book(2, "B 2", series = "S", seriesIndex = 1.0),
            book(3, "B 2", series = "S", seriesIndex = 0.5),
            book(5, "A", series = "S", seriesIndex = 1.0),
            book(4, "B 2", series = "S", seriesIndex = 1.0),
        )
        assertEquals(listOf(3L, 5L, 2L, 4L, 1L), books.inVolumeOrder().map { it.key.fileId })
    }

    @Test
    fun decimalVolumesSitBetweenTheirNeighbours() {
        val books = listOf(
            book(1, "S 11", series = "S"),
            book(2, "S 10.5", series = "S"),
            book(3, "S 10", series = "S"),
        )
        assertEquals(listOf(3L, 2L, 1L), books.inVolumeOrder().map { it.key.fileId })
    }
}
