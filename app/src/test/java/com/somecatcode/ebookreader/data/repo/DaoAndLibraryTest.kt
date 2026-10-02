package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.addAccount
import com.somecatcode.ebookreader.data.db.BookTagEntity
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.PendingEditEntity
import com.somecatcode.ebookreader.data.db.ProgressEntity
import com.somecatcode.ebookreader.data.db.ShelfBookEntity
import com.somecatcode.ebookreader.data.db.ShelfEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaoAndLibraryTest : DbTest() {

    private fun progress(fileId: Long, dirty: Boolean = false, at: Long = 10, account: String = "acc1") =
        ProgressEntity(account, fileId, """{"href":"a"}""", 0.3, "Pixel", at, 0, dirty)

    @Test
    fun deletingAnAccountCascadesToAllTables() = runBlocking {
        db.bookDao().upsertAll(listOf(book(1)))
        db.bookTagDao().insertAll(listOf(BookTagEntity("acc1", 1, "genre", "Fantasy")))
        db.progressDao().upsert(progress(1))
        db.shelfDao().upsertShelves(listOf(ShelfEntity("acc1", 7, "Fav", "manual", null, 1, "[]", 0, 0, 0)))
        db.shelfDao().insertMembers(listOf(ShelfBookEntity("acc1", 7, 1, 0)))
        db.downloadDao().upsert(DownloadEntity("acc1", 1, "DONE", 5, 5, "/x", null, "BOOK", null, null, 0))
        db.pendingEditDao().upsert(PendingEditEntity(accountId = "acc1", fileId = 1, kind = "metadata", patch = "{}", createdAt = 1, lastError = null))
        db.addAccount("acc2")
        db.bookDao().upsertAll(listOf(book(1, accountId = "acc2")))

        db.accountDao().delete("acc1")

        assertNull(db.bookDao().get("acc1", 1))
        assertTrue(db.bookTagDao().forBook("acc1", 1).isEmpty())
        assertNull(db.progressDao().get("acc1", 1))
        assertTrue(db.shelfDao().getAll("acc1").isEmpty())
        assertTrue(db.shelfDao().fileIds("acc1", 7).isEmpty())
        assertNull(db.downloadDao().get("acc1", 1))
        assertTrue(db.pendingEditDao().forAccount("acc1").isEmpty())
        assertNotNull("other account untouched", db.bookDao().get("acc2", 1))
    }

    @Test
    fun softDeleteHidesBooksUntilPurged() = runBlocking {
        db.bookDao().upsertAll(listOf(book(1), book(2)))
        db.bookDao().markDeleted("acc1", listOf(2))
        assertEquals(listOf(1L), db.bookDao().observeLibrary(listOf("acc1"), null, null).first().map { it.fileId })
        db.bookDao().purgeDeleted("acc1")
        assertNull(db.bookDao().get("acc1", 2))
    }

    @Test
    fun dirtyProgressQuery() = runBlocking {
        db.progressDao().upsertAll(listOf(progress(1, dirty = true, at = 5), progress(2, dirty = false), progress(3, dirty = true, at = 2)))
        assertEquals(listOf(3L, 1L), db.progressDao().dirty("acc1").map { it.fileId })
    }

    @Test
    fun replaceTagsIsAtomicReplacement() = runBlocking {
        db.bookDao().upsertAll(listOf(book(1)))
        db.bookTagDao().replaceForBook("acc1", 1, listOf(BookTagEntity("acc1", 1, "genre", "A"), BookTagEntity("acc1", 1, "tag", "x")))
        db.bookTagDao().replaceForBook("acc1", 1, listOf(BookTagEntity("acc1", 1, "genre", "B")))
        assertEquals(listOf("B"), db.bookTagDao().forBook("acc1", 1).map { it.name })
    }

    @Test
    fun libraryRepositoryFiltersSortsAndDecodes() = runBlocking {
        val repo = LibraryRepositoryImpl(db)
        db.bookDao().upsertAll(
            listOf(
                book(1, title = "Zeta", series = "Saga", seriesIndex = 2.0),
                book(2, title = "alpha", series = "Saga", seriesIndex = 1.0),
                book(3, title = "Mid", readStatus = "finished"),
            ),
        )
        db.bookTagDao().insertAll(
            listOf(BookTagEntity("acc1", 1, "genre", "Fantasy"), BookTagEntity("acc1", 2, "genre", "Fantasy"), BookTagEntity("acc1", 3, "tag", "x")),
        )
        db.progressDao().upsert(progress(1, at = 50))
        db.downloadDao().upsert(DownloadEntity("acc1", 2, "DONE", 9, 9, "/f", null, "BOOK", null, null, 0))

        assertEquals(listOf("alpha", "Mid", "Zeta"), repo.books(listOf("acc1")).first().map { it.title })
        assertEquals(listOf("alpha", "Zeta"), repo.books(listOf("acc1"), LibraryFilter(genres = setOf("Fantasy"))).first().map { it.title })
        assertEquals(listOf("alpha", "Zeta"), repo.books(listOf("acc1"), LibraryFilter(series = "Saga", sort = LibrarySort.SERIES)).first().map { it.title })
        assertEquals(listOf("Mid"), repo.books(listOf("acc1"), LibraryFilter(status = com.somecatcode.ebookreader.data.api.ReadStatus.FINISHED)).first().map { it.title })
        assertEquals(listOf("alpha"), repo.books(listOf("acc1"), LibraryFilter(onlyOffline = true)).first().map { it.title })
        assertEquals(listOf("Zeta"), repo.continueReading(listOf("acc1")).first().map { it.title })

        val series = repo.series(listOf("acc1")).first().single()
        assertEquals("Saga", series.name)
        assertEquals(2, series.count)
        val first = repo.books(listOf("acc1"), LibraryFilter(search = "zet")).first().single()
        assertEquals(0.3, first.percentage!!, 0.0)
        assertEquals(listOf("Fantasy"), first.genres)
        assertFalse(first.offline.isAvailableOffline)
    }

    @Test
    fun manualAndSmartShelvesResolveMembers() = runBlocking {
        val repo = LibraryRepositoryImpl(db)
        db.bookDao().upsertAll(listOf(book(1), book(2), book(3)))
        db.bookTagDao().insertAll(listOf(BookTagEntity("acc1", 2, "genre", "Sci-Fi/Space"), BookTagEntity("acc1", 3, "genre", "Sci-Fi")))
        db.shelfDao().upsertShelves(
            listOf(
                ShelfEntity("acc1", 7, "Manual", "manual", null, 1, "[]", 0, 0, 0),
                ShelfEntity(
                    "acc1", 8, "Smart", "smart",
                    """{"include":["genre:Sci-Fi/*"],"exclude":[],"match":"all","search":"","sort":"title","order":"asc"}""",
                    0, "[]", 0, 0, 0,
                ),
            ),
        )
        db.shelfDao().insertMembers(listOf(ShelfBookEntity("acc1", 7, 1, 0)))
        assertEquals(listOf(1L), repo.books(listOf("acc1"), LibraryFilter(shelf = ShelfKey("acc1", 7))).first().map { it.key.fileId })
        assertEquals(setOf(2L, 3L), repo.books(listOf("acc1"), LibraryFilter(shelf = ShelfKey("acc1", 8))).first().map { it.key.fileId }.toSet())
        assertEquals(2, repo.shelves(listOf("acc1")).first().size)
    }
}
