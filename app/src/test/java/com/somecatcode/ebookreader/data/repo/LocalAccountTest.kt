package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.FakeSettings
import com.somecatcode.ebookreader.data.RecordingScheduler
import com.somecatcode.ebookreader.data.addAccount
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.EbookApi
import com.somecatcode.ebookreader.data.api.Locations
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.api.MetadataPatch
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.db.AccountKind
import com.somecatcode.ebookreader.data.db.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Local libraries: everything stays in Room, nothing is scheduled for upload and no server is asked. */
class LocalAccountTest : DbTest() {

    private val scheduler = RecordingScheduler()
    private val noServer = object : ApiClientFactory {
        override suspend fun forAccount(accountId: String): EbookApi = throw AssertionError("a local library must not use the API")
        override fun forCredentials(serverUrl: String, loginName: String, appPassword: String): EbookApi = throw AssertionError()
    }
    private val key = BookKey("loc", 9)

    @Before
    fun addLocal() = runBlocking {
        db.addAccount("loc", kind = AccountKind.LOCAL)
        db.bookDao().upsertAll(listOf(book(9, accountId = "loc").copy(localUri = "content://tree/doc9", size = 500)))
    }

    @Test
    fun progressIsCleanAndNeverScheduledOrCheckedRemotely() = runBlocking {
        val repo = ProgressRepositoryImpl(db, noServer, FakeSettings(), scheduler, defaultDeviceName = { "Pixel" })
        repo.saveLocal(key, Locator("c1.xhtml", locations = Locations(totalProgression = 0.5)), 0.5)
        assertFalse(db.progressDao().get("loc", 9)!!.dirty)
        assertTrue(scheduler.scheduled.isEmpty())
        assertNull(repo.checkRemote(key))
        repo.pushDirty("loc")
        assertEquals("reading", db.bookDao().get("loc", 9)!!.readStatus)
    }

    @Test
    fun annotationsAreCleanAndDeletedRowsDisappear() = runBlocking {
        val repo = AnnotationRepositoryImpl(db, noServer, scheduler)
        val uuid = repo.create(key, Locator("c1.xhtml"), "Text", AnnotationColor.GREEN, null)
        assertFalse(db.annotationDao().get("loc", uuid)!!.dirty)
        repo.setNote(key, uuid, "Notiz")
        assertFalse(db.annotationDao().get("loc", uuid)!!.dirty)
        repo.delete(key, uuid)
        assertNull("no tombstone without a server", db.annotationDao().get("loc", uuid))
        repo.refresh(key)
        repo.pushDirty("loc")
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun editsStayInTheAppWithoutPendingUploads() = runBlocking {
        val repo = EditRepositoryImpl(db, noServer, scheduler)
        repo.editMetadata(key, MetadataPatch(mapOf("title" to JsonPrimitive("Neu"))))
        repo.editAppData(key, AppDataPatch(rating = 4, setRating = true, readStatus = ReadStatus.FINISHED))
        val book = db.bookDao().get("loc", 9)!!
        assertEquals("Neu", book.title)
        assertEquals(4, book.rating)
        assertEquals(0, repo.pendingCount.first())
        repo.flushPending("loc")
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun localBooksAreAlwaysAvailableOffline() = runBlocking {
        val library = LibraryRepositoryImpl(db)
        val b = library.book(key).first()!!
        assertTrue(b.local)
        assertEquals(DownloadState.DONE, b.offline.state)
        assertEquals(listOf(9L), library.books(listOf("loc", "acc1"), LibraryFilter(onlyOffline = true)).first().map { it.key.fileId })
    }

    @Test
    fun shelvesOfALocalLibraryAreNeverFetched() = runBlocking {
        ShelfRepositoryImpl(db, noServer).refresh("loc")
    }
}
