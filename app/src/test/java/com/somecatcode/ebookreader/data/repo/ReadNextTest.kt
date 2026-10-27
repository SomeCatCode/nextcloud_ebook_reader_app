package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.ui.book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadNextTest {

    private fun ids(list: List<LibraryBook>) = list.map { it.key.fileId }

    @Test
    fun nextVolumeFollowsTheNaturalOrder() {
        val library = listOf(
            book(1, "Saga 1", series = "Saga", status = ReadStatus.FINISHED),
            book(2, "Saga 2", series = "Saga"),
            book(10, "Saga 10", series = "Saga"),
        )
        assertEquals(2L, ReadNext.nextVolume(library[0], library)!!.key.fileId)
        assertEquals(10L, ReadNext.nextVolume(library[1], library)!!.key.fileId)
        assertNull("last volume", ReadNext.nextVolume(library[2], library))
    }

    @Test
    fun nextVolumeIsOnlyOfferedWhenNotFinishedAndOnlyWithinTheSameAccount() {
        val library = listOf(
            book(1, "Saga 1", series = "Saga"),
            book(2, "Saga 2", series = "Saga", status = ReadStatus.FINISHED),
            book(3, "Saga 3", series = "Saga", accountId = "other"),
            book(4, "Standalone"),
        )
        assertNull("the next volume is finished", ReadNext.nextVolume(library[0], library))
        assertNull("a book without series has no next volume", ReadNext.nextVolume(library[3], library))
        assertNull("the volume of another account does not count", ReadNext.nextVolume(library[1], library))
    }

    @Test
    fun similarScoresAuthorsGenresTagsLanguageAndFormatFamily() {
        val me = book(1, "Me", authors = listOf("Ann"), genres = listOf("SciFi", "Space"), tags = listOf("epic"))
        val sameAuthor = book(2, "A", authors = listOf("ann")) // 3 + language 1 + family 1 = 5
        val sameGenres = book(3, "B", authors = listOf("X"), genres = listOf("scifi", "space")) // 4 + 1 + 1 = 6
        val sameTag = book(4, "C", authors = listOf("X"), tags = listOf("EPIC")) // 1 + 1 + 1 = 3
        val comic = book(5, "D", authors = listOf("X"), format = "cbz", language = "de") // 0: other family, other language
        val finished = book(6, "E", authors = listOf("Ann"), status = ReadStatus.FINISHED)
        val reading = book(7, "F", authors = listOf("Ann"), status = ReadStatus.READING)
        val result = ReadNext.similar(me, listOf(me, sameAuthor, sameGenres, sameTag, comic, finished, reading))
        assertEquals(listOf(3L, 2L, 4L), ids(result))
    }

    @Test
    fun similarNeverContainsTheSameSeriesAndOneBookPerOtherSeries() {
        val me = book(1, "Me 1", authors = listOf("Ann"), series = "Mine")
        val library = listOf(
            me,
            book(2, "Me 2", authors = listOf("Ann"), series = "Mine"),
            book(13, "Other 10", authors = listOf("Ann"), series = "Other"),
            book(12, "Other 2", authors = listOf("Ann"), series = "Other"),
            book(14, "Other 3", authors = listOf("Ann"), series = "other"),
            book(20, "Solo", authors = listOf("Ann")),
        )
        // "Other 2" is the first unread volume of its series in reading order
        assertEquals(setOf(12L, 20L), ids(ReadNext.similar(me, library)).toSet())
        assertEquals(2, ReadNext.similar(me, library).size)
    }

    @Test
    fun ofASeriesTheFirstUnreadVolumeInReadingOrderRepresentsIt() {
        val me = book(1, "Me", authors = listOf("Ann"))
        val library = listOf(
            me,
            book(2, "S 1", authors = listOf("Ann"), series = "S", status = ReadStatus.FINISHED),
            book(3, "S 2", authors = listOf("Ann"), series = "S"),
            book(4, "S 3", authors = listOf("Ann"), series = "S"),
        )
        assertEquals(listOf(3L), ids(ReadNext.similar(me, library)))
    }

    @Test
    fun tiesPreferNewerBooksThenTitleAndTheLimitIsRespected() {
        val me = book(1, "Me", authors = listOf("Ann"))
        val library = listOf(
            me,
            book(2, "B", authors = listOf("Ann"), addedAt = 100),
            book(3, "A", authors = listOf("Ann"), addedAt = 100),
            book(4, "C", authors = listOf("Ann"), addedAt = 200),
            book(5, "D", authors = listOf("Ann"), addedAt = 50),
        )
        assertEquals(listOf(4L, 3L, 2L, 5L), ids(ReadNext.similar(me, library)))
        assertEquals(listOf(4L, 3L), ids(ReadNext.similar(me, library, limit = 2)))
    }

    @Test
    fun onlyScoresAboveZeroCount() {
        val me = book(1, "Me", authors = listOf("Ann"))
        val comic = book(2, "Comic", authors = listOf("Zed"), format = "cbz", language = "de")
        assertTrue(ReadNext.similar(me, listOf(me, comic)).isEmpty())
        assertTrue(ReadNext.compute(me, listOf(me, comic)).isEmpty)
    }

    @Test
    fun computeCombinesNextAndSimilar() {
        val me = book(1, "Saga 1", series = "Saga", authors = listOf("Ann"))
        val library = listOf(me, book(2, "Saga 2", series = "Saga", authors = listOf("Ann")), book(3, "Other", authors = listOf("Ann")))
        val result = ReadNext.compute(me, library)
        assertEquals(2L, result.next!!.key.fileId)
        assertEquals(listOf(3L), ids(result.similar))
    }
}
