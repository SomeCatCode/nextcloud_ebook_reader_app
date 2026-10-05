package com.somecatcode.ebookreader.data.sync

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.FakeSettings
import com.somecatcode.ebookreader.data.RecordingDownloadManager
import com.somecatcode.ebookreader.data.RecordingScheduler
import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.StaticApiFactory
import com.somecatcode.ebookreader.data.bookJson
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.db.ProgressEntity
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.ocs
import com.somecatcode.ebookreader.data.ocsError
import com.somecatcode.ebookreader.data.progressJson
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.EditRepositoryImpl
import com.somecatcode.ebookreader.data.repo.ProgressRepositoryImpl
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncEngineTest : DbTest() {

    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)
    private val downloads = RecordingDownloadManager()
    private lateinit var engine: SyncEngineImpl

    @Before
    fun setUp() {
        val factory = StaticApiFactory(server.ebookApi())
        val scheduler = RecordingScheduler()
        val edits = EditRepositoryImpl(db, factory, scheduler)
        val progress = ProgressRepositoryImpl(db, factory, FakeSettings(), scheduler, { "Pixel" })
        engine = SyncEngineImpl(
            db, factory, edits, progress, downloads,
            workManager = { error("WorkManager is not used in these tests") },
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { 5_000 },
        )
        dispatcher.on("GET", "/shelves") { ocs("""{"shelves":[]}""") }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun syncPage(books: String = "", deleted: String = "", progress: String = "", cursor: String, more: Boolean = false) =
        ocs("""{"books":[$books],"deleted":[$deleted],"progress":[$progress],"cursor":"$cursor","hasMore":$more}""")

    private fun cursorOf(req: okhttp3.mockwebserver.RecordedRequest) = req.requestUrl!!.queryParameter("cursor")

    @Test
    fun fullSyncPagesThroughCursorsAndStoresEverything() = runBlocking {
        dispatcher.on("GET", "/sync") { req ->
            when (cursorOf(req)) {
                "" -> syncPage(
                    books = bookJson(1, genres = listOf("Fantasy"), tags = listOf("x")),
                    progress = progressJson(1, clientUpdatedAt = 50),
                    cursor = "c1", more = true,
                )
                else -> syncPage(books = bookJson(2), cursor = "c2")
            }
        }
        val outcome = engine.syncNow("acc1") as SyncOutcome.Success

        assertEquals(2, outcome.booksChanged)
        assertEquals(1, outcome.progressMerged)
        assertEquals(listOf("", "c1"), dispatcher.requests.filter { it.path!!.contains("/sync") }.map { cursorOf(it) })
        val account = db.accountDao().get("acc1")!!
        assertEquals("c2", account.lastSyncCursor)
        assertEquals(5_000L, account.lastSyncAt)
        assertEquals(setOf("genre:Fantasy", "tag:x"), db.bookTagDao().forBook("acc1", 1).map { "${it.type}:${it.name}" }.toSet())
        assertEquals(0.5, db.progressDao().get("acc1", 1)!!.percentage, 0.0)
        assertEquals(1, downloads.resumed)
    }

    @Test
    fun cursorIsOnlySavedAfterThePageWasCommitted() = runBlocking {
        dispatcher.on("GET", "/sync") { req ->
            when (cursorOf(req)) {
                "" -> syncPage(books = bookJson(1), cursor = "c1", more = true)
                else -> ocsError(500, "boom") // second page fails
            }
        }
        val outcome = engine.syncNow("acc1")
        assertEquals(SyncOutcome.Failure(SyncError.SERVER, retryable = true), outcome)
        // page 1 is committed together with its cursor; the failed page 2 changed nothing
        assertEquals("c1", db.accountDao().get("acc1")!!.lastSyncCursor)
        assertNotNull(db.bookDao().get("acc1", 1))
        assertNull(db.bookDao().get("acc1", 2))
    }

    @Test
    fun resumedRunStartsFromTheSavedCursor() = runBlocking {
        db.accountDao().updateSync("acc1", "c1", null)
        db.bookDao().upsertAll(listOf(book(1)))
        dispatcher.on("GET", "/sync") { syncPage(books = bookJson(2), cursor = "c2") }
        engine.syncNow("acc1")
        assertEquals("c1", cursorOf(dispatcher.requests.first { it.path!!.contains("/sync") }))
        assertEquals("c2", db.accountDao().get("acc1")!!.lastSyncCursor)
        assertNotNull(db.bookDao().get("acc1", 1)) // delta sync must not remove what it did not list
    }

    @Test
    fun tombstonesRemoveBooksTagsProgressAndDownloads() = runBlocking {
        db.accountDao().updateSync("acc1", "c1", null)
        db.bookDao().upsertAll(listOf(book(1), book(2)))
        db.progressDao().upsert(ProgressEntity("acc1", 2, """{"href":"a"}""", 0.1, null, 1, 1, false))
        dispatcher.on("GET", "/sync") { syncPage(deleted = "2", cursor = "c2") }

        val outcome = engine.syncNow("acc1") as SyncOutcome.Success

        assertEquals(1, outcome.booksDeleted)
        assertNull(db.bookDao().get("acc1", 2))
        assertNotNull(db.bookDao().get("acc1", 1))
        assertNull(db.progressDao().get("acc1", 2))
        assertEquals(listOf(BookKey("acc1", 2)), downloads.deleted)
    }

    @Test
    fun invalidCursorFallsBackToFullSyncAndDropsVanishedBooks() = runBlocking {
        db.accountDao().updateSync("acc1", "stale-cursor", null)
        db.bookDao().upsertAll(listOf(book(1), book(99)))
        dispatcher.on("GET", "/sync") { req ->
            if (cursorOf(req) == "stale-cursor") ocsError(400, "Invalid cursor") else syncPage(books = bookJson(1) + "," + bookJson(2), cursor = "fresh")
        }

        val outcome = engine.syncNow("acc1") as SyncOutcome.Success

        assertEquals(listOf("stale-cursor", ""), dispatcher.requests.filter { it.path!!.contains("/sync") }.map { cursorOf(it) })
        assertEquals("fresh", db.accountDao().get("acc1")!!.lastSyncCursor)
        assertNotNull(db.bookDao().get("acc1", 2))
        assertNull("book unknown to the server after a full sync is gone", db.bookDao().get("acc1", 99))
        assertEquals(1, outcome.booksDeleted)
        assertTrue(downloads.deleted.contains(BookKey("acc1", 99)))
    }

    @Test
    fun progressMergeKeepsNewerDirtyLocalRow() = runBlocking {
        db.progressDao().upsertAll(
            listOf(
                ProgressEntity("acc1", 1, """{"href":"local"}""", 0.9, "Pixel", 9_000, 0, dirty = true),
                ProgressEntity("acc1", 2, """{"href":"old"}""", 0.1, "Pixel", 10, 0, dirty = true),
            ),
        )
        dispatcher.on("POST", "/progress/batch") { MockResponse().setResponseCode(503) } // push fails, rows stay dirty
        dispatcher.on("GET", "/sync") {
            syncPage(
                progress = progressJson(1, "remote", 0.2, clientUpdatedAt = 100) + "," + progressJson(2, "remote", 0.6, clientUpdatedAt = 100),
                cursor = "c1",
            )
        }
        engine.syncNow("acc1")
        assertEquals("local", db.progressDao().get("acc1", 1)!!.locator.substringAfter("\"href\":\"").substringBefore("\""))
        assertTrue(db.progressDao().get("acc1", 1)!!.dirty)
        val second = db.progressDao().get("acc1", 2)!!
        assertEquals(0.6, second.percentage, 0.0)
        assertFalse(second.dirty)
    }

    @Test
    fun changedFileOnServerRequeuesTheDownload() = runBlocking {
        db.accountDao().updateSync("acc1", "c1", null)
        db.bookDao().upsertAll(listOf(book(1, mtime = 1, size = 1000)))
        db.downloadDao().upsert(DownloadEntity("acc1", 1, "DONE", 1000, 1000, "/f.epub", "\"e\"", "SERIES", "Saga", null, 0))
        dispatcher.on("GET", "/sync") { syncPage(books = bookJson(1, mtime = 99, size = 2000), cursor = "c2") }

        engine.syncNow("acc1")

        val row = db.downloadDao().get("acc1", 1)!!
        assertEquals("QUEUED", row.state)
        assertEquals(2000L, row.total)
        assertEquals(Triple(BookKey("acc1", 1), PinnedBy.SERIES, "Saga"), downloads.enqueued.single())
    }

    @Test
    fun newBooksOfPinnedSeriesAndShelvesAreEnqueued() = runBlocking {
        db.accountDao().updateSync("acc1", "c1", null)
        db.bookDao().upsertAll(listOf(book(1, series = "Saga", seriesIndex = 1.0)))
        db.downloadDao().upsert(DownloadEntity("acc1", 1, "DONE", 5, 5, "/f", null, "SERIES", "Saga", null, 0))
        db.downloadDao().upsert(DownloadEntity("acc1", 50, "DONE", 5, 5, "/g", null, "SHELF", "7", null, 0))
        dispatcher.on("GET", "/sync") { syncPage(books = bookJson(2, series = "Saga", seriesIndex = 2.0) + "," + bookJson(3), cursor = "c2") }
        dispatcher.on("GET", "/shelves") {
            ocs("""{"shelves":[{"id":7,"name":"Fav","type":"manual","count":1,"coverFileIds":[],"sortOrder":0,"createdAt":1,"updatedAt":2}]}""")
        }
        dispatcher.on("GET", "/books") { ocs("""{"books":[${bookJson(3)}],"total":1}""") }

        engine.syncNow("acc1")

        val enqueued = downloads.enqueued.map { Triple(it.first.fileId, it.second, it.third) }.toSet()
        assertTrue(enqueued.contains(Triple(2L, PinnedBy.SERIES, "Saga")))
        assertTrue(enqueued.contains(Triple(3L, PinnedBy.SHELF, "7")))
        assertFalse("already downloaded book 1 is not re-enqueued", enqueued.any { it.first == 1L })
    }

    @Test
    fun manualShelfMembershipIsResolvedThroughBookList() = runBlocking {
        dispatcher.on("GET", "/sync") { syncPage(books = bookJson(1) + "," + bookJson(2), cursor = "c1") }
        dispatcher.on("GET", "/shelves") {
            ocs(
                """{"shelves":[{"id":7,"name":"Fav","type":"manual","count":2,"coverFileIds":[1,2],"sortOrder":0,"createdAt":1,"updatedAt":2},
                   {"id":8,"name":"Smart","type":"smart","query":{"include":["genre:Fantasy"]},"count":0,"coverFileIds":[],"sortOrder":1,"createdAt":1,"updatedAt":2}]}""",
            )
        }
        dispatcher.on("GET", "/books") { ocs("""{"books":[${bookJson(2)},${bookJson(1)}],"total":2}""") }

        engine.syncNow("acc1")

        assertEquals(2, db.shelfDao().getAll("acc1").size)
        assertEquals(listOf(2L, 1L), db.shelfDao().fileIds("acc1", 7)) // order from the server preserved
        val request = dispatcher.requests.first { it.path!!.contains("/books") }
        assertEquals(listOf("shelf:7"), request.requestUrl!!.queryParameterValues("include[]"))
        assertEquals("shelf", request.requestUrl!!.queryParameter("sort"))

        // membership changes do not touch the shelf row, so every sync reloads manual shelves
        dispatcher.requests.clear()
        dispatcher.on("GET", "/books") { ocs("""{"books":[${bookJson(1)}],"total":1}""") }
        engine.syncNow("acc1")
        assertTrue(dispatcher.requests.any { it.path!!.contains("/books") })
        assertEquals(listOf(1L), db.shelfDao().fileIds("acc1", 7))
    }

    @Test
    fun errorsAreMappedToSyncErrors() = runBlocking {
        dispatcher.on("GET", "/sync") { MockResponse().setResponseCode(401).setBody("{}") }
        assertEquals(SyncOutcome.Failure(SyncError.UNAUTHORIZED, retryable = false), engine.syncNow("acc1"))

        val missing = RoutingDispatcher() // every route 404: app not installed / too old
        serverWith(missing).let { s ->
            val factory = StaticApiFactory(s.ebookApi())
            val e = SyncEngineImpl(
                db, factory, EditRepositoryImpl(db, factory, RecordingScheduler()),
                ProgressRepositoryImpl(db, factory, FakeSettings(), RecordingScheduler(), { "x" }),
                downloads, { error("unused") }, CoroutineScope(Dispatchers.Unconfined),
            )
            assertEquals(SyncOutcome.Failure(SyncError.APP_UNAVAILABLE, retryable = false), e.syncNow("acc1"))
            s.shutdown()
        }

        server.shutdown()
        assertEquals(SyncOutcome.Failure(SyncError.OFFLINE, retryable = true), engine.syncNow("acc1"))
    }

    @Test
    fun unknownAccountFails() = runBlocking {
        assertEquals(SyncOutcome.Failure(SyncError.UNKNOWN, retryable = false), engine.syncNow("nope"))
    }
}
