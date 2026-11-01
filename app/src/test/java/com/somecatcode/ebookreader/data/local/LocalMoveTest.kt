package com.somecatcode.ebookreader.data.local

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.addAccount
import com.somecatcode.ebookreader.data.db.AccountKind
import com.somecatcode.ebookreader.data.db.AnnotationEntity
import com.somecatcode.ebookreader.data.db.BookTagEntity
import com.somecatcode.ebookreader.data.db.ProgressEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A renamed or moved file of a local library keeps its book (progress, highlights, rating). */
class LocalMoveTest : DbTest() {

    private fun gone(fileId: Long, name: String, size: Long = 1000, mtime: Long = 5) =
        book(fileId, accountId = "loc", size = size).copy(path = "/Old/$name", fileEtag = "$mtime-$size", localUri = "content://x/$fileId")

    private fun found(id: String, name: String, size: Long = 1000, modified: Long = 5, folder: String = "/New") =
        FoundFile(id, name, "$folder/$name", "epub", size, modified)

    @Test
    fun renameKeepsTheModificationTimeAndMoveKeepsTheName() {
        val renamed = found("d1", "Neuer Titel.epub")
        val moved = found("d2", "b.epub", size = 2000, modified = 99)
        val pairs = matchMovedFiles(
            listOf(renamed, moved, found("d3", "c.epub", size = 3000)),
            listOf(gone(1, "a.epub"), gone(2, "b.epub", size = 2000), gone(4, "x.epub", size = 4000)),
        )
        assertEquals(listOf("d1" to 1L, "d2" to 2L), pairs.map { it.first.documentId to it.second.fileId })
    }

    @Test
    fun ambiguousCandidatesAreLeftAlone() {
        // two books of the same size and time: no guessing
        val pairs = matchMovedFiles(listOf(found("d1", "new.epub")), listOf(gone(1, "a.epub"), gone(2, "b.epub")))
        assertTrue(pairs.isEmpty())
        // a different size is never the same file
        assertTrue(matchMovedFiles(listOf(found("d1", "a.epub", size = 1)), listOf(gone(1, "a.epub"))).isEmpty())
    }

    @Test
    fun movingABookCarriesProgressHighlightsAndTags() = runBlocking {
        db.addAccount("loc", kind = AccountKind.LOCAL)
        val old = gone(1, "a.epub").copy(rating = 4, readStatus = "reading")
        db.bookDao().upsertAll(listOf(old))
        db.bookTagDao().insertAll(listOf(BookTagEntity("loc", 1, "genre", "Krimi")))
        db.progressDao().upsert(ProgressEntity("loc", 1, """{"href":"c2.xhtml"}""", 0.4, "Pixel", 10, 0, false))
        db.annotationDao().upsert(AnnotationEntity("loc", "u1", 1, "highlight", """{"href":"c2.xhtml"}""", "Text", null, "green", 1, 0, 1, false, false))

        val moved = moveLocalBook(db, old, 77, "/New/a.epub", "content://x/77")

        assertEquals(77L, moved.fileId)
        assertNull(db.bookDao().get("loc", 1))
        val book = db.bookDao().get("loc", 77)!!
        assertEquals("/New/a.epub", book.path)
        assertEquals(4, book.rating)
        assertEquals("reading", book.readStatus)
        assertEquals(0.4, db.progressDao().get("loc", 77)!!.percentage, 0.0)
        assertNull(db.progressDao().get("loc", 1))
        assertEquals(77L, db.annotationDao().get("loc", "u1")!!.fileId)
        assertEquals(listOf("Krimi"), db.bookTagDao().forBook("loc", 77).map { it.name })
    }
}
