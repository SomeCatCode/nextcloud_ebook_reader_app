package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.ui.book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HomeSectionsTest {

    private fun ids(list: List<LibraryBook>) = list.map { it.key.fileId }

    private val done = OfflineState(DownloadState.DONE, 10, 10)

    private fun shelf(id: Long, count: Int = 3, account: String = "acc1") =
        ShelfInfo(ShelfKey(account, id), "Shelf $id", smart = false, count = count, coverFileIds = emptyList())

    @Test
    fun continueReadingKeepsOnlyReadingBooksInTheGivenOrder() {
        val library = listOf(
            book(1, "Unread"),
            book(2, "Reading B", status = ReadStatus.READING),
            book(3, "Done", status = ReadStatus.FINISHED),
            book(4, "Reading A", status = ReadStatus.READING),
        )
        assertEquals(listOf(2L, 4L), ids(HomeLogic.continueReading(library)))
        assertEquals(listOf(2L), ids(HomeLogic.continueReading(library, limit = 1)))
    }

    @Test
    fun offlineListsDownloadedAndLocalBooksNewestFirst() {
        val library = listOf(
            book(1, "Old download", offline = done, addedAt = 10),
            book(2, "Not downloaded", addedAt = 50),
            book(3, "New download", offline = done, addedAt = 30),
            book(4, "Local", addedAt = 20).copy(local = true),
            book(5, "Downloading", offline = OfflineState(DownloadState.RUNNING, 1, 10), addedAt = 40),
        )
        assertEquals(listOf(3L, 4L, 1L), ids(HomeLogic.offline(library)))
    }

    @Test
    fun suggestionsUseTheLastReadBooksAndSkipContinueReadingAndFinished() {
        val library = listOf(
            book(1, "Reading Fantasy", status = ReadStatus.READING, authors = listOf("A"), genres = listOf("Fantasy")),
            book(2, "Finished SciFi", status = ReadStatus.FINISHED, authors = listOf("B"), genres = listOf("SciFi")),
            book(3, "Fantasy match", authors = listOf("C"), genres = listOf("Fantasy")),
            book(4, "SciFi match", authors = listOf("D"), genres = listOf("SciFi")),
            book(5, "No match", authors = listOf("E"), genres = listOf("Cooking")),
            book(6, "Other reading", status = ReadStatus.READING, authors = listOf("F"), genres = listOf("Fantasy")),
        )
        val result = HomeLogic.suggestions(library, exclude = setOf(BookKey("acc1", 6)))
        assertEquals(setOf(3L, 4L), ids(result).toSet())
        assertFalse("seeds are never suggested", ids(result).any { it in listOf(1L, 2L, 6L) })
    }

    @Test
    fun suggestionsMergeSeedsRoundRobinAndDedupe() {
        val library = listOf(
            book(1, "Saga 1", series = "Saga", status = ReadStatus.FINISHED, authors = listOf("A"), genres = listOf("Fantasy")),
            book(2, "Saga 2", series = "Saga", authors = listOf("A"), genres = listOf("Fantasy")),
            book(3, "Space 1", series = "Space", status = ReadStatus.FINISHED, authors = listOf("B"), genres = listOf("SciFi")),
            book(4, "Space 2", series = "Space", authors = listOf("B"), genres = listOf("SciFi")),
            book(5, "Both", authors = listOf("C"), genres = listOf("Fantasy", "SciFi")),
        )
        val result = HomeLogic.suggestions(library)
        // first the next volumes of both seeds, then the book that both seeds suggest (once)
        assertEquals(setOf(2L, 4L, 5L), ids(result).toSet())
        assertEquals(3, result.size)
        assertEquals(2, ids(result).indexOf(5L))
    }

    @Test
    fun suggestionsAreLimitedAndEmptyWithoutReadBooks() {
        assertTrue(HomeLogic.suggestions(listOf(book(1, "Unread", genres = listOf("X")))).isEmpty())
        val library = listOf(book(1, "Seed", status = ReadStatus.FINISHED, genres = listOf("X"))) +
            (2L..20L).map { book(it, "Book $it", genres = listOf("X"), authors = listOf("Author $it")) }
        assertEquals(5, HomeLogic.suggestions(library, limit = 5).size)
        assertEquals(HomeLogic.SUGGESTION_LIMIT, HomeLogic.suggestions(library).size)
    }

    @Test
    fun pickShelvesUsesOnlySelectedShelvesWithBooks() {
        val shelves = listOf(shelf(1), shelf(2, count = 0), shelf(3), shelf(4))
        val selected = setOf("1:acc1", "2:acc1", "3:acc1")
        repeat(20) { seed ->
            val picked = HomeLogic.pickShelves(shelves, selected, Random(seed)).map { it.key.shelfId }
            assertTrue(picked.all { it in listOf(1L, 3L) })
            assertEquals(picked.distinct(), picked)
        }
        assertTrue(HomeLogic.pickShelves(shelves, emptySet(), Random(1)).isEmpty())
        assertTrue(HomeLogic.pickShelves(emptyList(), selected, Random(1)).isEmpty())
    }

    @Test
    fun pickShelvesPicksTwoOrThreeAndIsDeterministicPerSeed() {
        val shelves = (1L..8L).map { shelf(it) }
        val selected = shelves.map { HomeLogic.shelfPref(it.key) }.toSet()
        val sizes = (0..60).map { HomeLogic.pickShelves(shelves, selected, Random(it)).size }.toSet()
        assertEquals(setOf(2, 3), sizes)
        assertEquals(
            HomeLogic.pickShelves(shelves, selected, Random(42)).map { it.key },
            HomeLogic.pickShelves(shelves, selected, Random(42)).map { it.key },
        )
        assertEquals("a single qualifying shelf", 1, HomeLogic.pickShelves(shelves, setOf("5:acc1"), Random(1)).size)
    }

    @Test
    fun buildHidesEmptySectionsAndReportsEmpty() {
        assertTrue(HomeLogic.build(emptyList()).isEmpty)
        val library = listOf(book(1, "Reading", status = ReadStatus.READING, offline = done))
        val sections = HomeLogic.build(library, listOf(HomeShelf(shelf(1), emptyList()), HomeShelf(shelf(2), library)))
        assertEquals(listOf(1L), ids(sections.continueReading))
        assertEquals(listOf(1L), ids(sections.offline))
        assertTrue(sections.suggestions.isEmpty())
        assertEquals(listOf(2L), sections.shelves.map { it.shelf.key.shelfId })
        assertFalse(sections.isEmpty)
    }
}
