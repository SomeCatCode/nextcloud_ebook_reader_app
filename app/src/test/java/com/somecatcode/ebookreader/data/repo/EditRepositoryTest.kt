package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.RecordingScheduler
import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.StaticApiFactory
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.MetadataPatch
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.bookJson
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.ocs
import com.somecatcode.ebookreader.data.ocsError
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditRepositoryTest : DbTest() {

    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)
    private val scheduler = RecordingScheduler()
    private lateinit var repo: EditRepositoryImpl
    private val key = BookKey("acc1", 1)

    @Before
    fun setUp() {
        repo = EditRepositoryImpl(db, StaticApiFactory(server.ebookApi()), scheduler, clock = { 1000 })
        runBlocking { db.bookDao().upsertAll(listOf(book(1, title = "Old"))) }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun metadataEditIsOptimisticAndMergedIntoOnePendingRow() = runBlocking {
        repo.editMetadata(key, MetadataPatch(mapOf("title" to JsonPrimitive("New"), "genres" to buildJsonArray { add(JsonPrimitive("Fantasy")) })))
        repo.editMetadata(key, MetadataPatch(mapOf("title" to JsonPrimitive("Newer"), "series" to JsonPrimitive("Saga"), "seriesIndex" to JsonPrimitive(2.5))))

        val book = db.bookDao().get("acc1", 1)!!
        assertEquals("Newer", book.title)
        assertEquals("Saga", book.series)
        assertEquals(2.5, book.seriesIndex!!, 0.0)
        assertEquals(listOf("Fantasy"), db.bookTagDao().forBook("acc1", 1).map { it.name })

        val rows = db.pendingEditDao().forAccount("acc1")
        assertEquals(1, rows.size)
        val patch = ApiJson.parseToJsonElement(rows.single().patch).jsonObject
        assertEquals(setOf("title", "genres", "series", "seriesIndex"), patch.keys)
        assertEquals(JsonPrimitive("Newer"), patch["title"])
        assertEquals(listOf("acc1", "acc1"), scheduler.scheduled)
        assertEquals(1, repo.pendingCount.first())
    }

    @Test
    fun appDataEditsMergeAndClearRatingWithNull() = runBlocking {
        repo.editAppData(key, AppDataPatch(setRating = true, rating = 4))
        repo.editAppData(key, AppDataPatch(readStatus = ReadStatus.FINISHED))
        repo.editAppData(key, AppDataPatch(setRating = true, rating = null))

        val book = db.bookDao().get("acc1", 1)!!
        assertEquals(null, book.rating)
        assertEquals("finished", book.readStatus)
        val rows = db.pendingEditDao().forAccount("acc1")
        assertEquals(1, rows.size)
        val patch = ApiJson.parseToJsonElement(rows.single().patch).jsonObject
        assertEquals(JsonNull, patch["rating"])
        assertEquals(JsonPrimitive("finished"), patch["readStatus"])
    }

    @Test
    fun statusFinishedSetsProgressToFullAndUnreadResetsIt() = runBlocking {
        db.progressDao().upsert(com.somecatcode.ebookreader.data.db.ProgressEntity("acc1", 1, """{"href":"c3.xhtml"}""", 0.4, "Pixel", 10, 10, false))
        repo.editAppData(key, AppDataPatch(readStatus = ReadStatus.FINISHED))
        assertEquals(1.0, db.progressDao().get("acc1", 1)!!.percentage, 0.0)
        repo.editAppData(key, AppDataPatch(readStatus = ReadStatus.UNREAD))
        assertEquals(null, db.progressDao().get("acc1", 1))
        assertEquals("unread", db.bookDao().get("acc1", 1)!!.readStatus)
    }

    @Test
    fun flushUploadsAndClearsPendingRows() = runBlocking {
        dispatcher.on("PATCH", "/metadata") { ocs("""{"book":${bookJson(1)},"warnings":[],"writeQueued":false}""") }
        dispatcher.on("PATCH", "/app-data") { ocs(bookJson(1)) }
        repo.editMetadata(key, MetadataPatch(mapOf("title" to JsonPrimitive("New"))))
        repo.editAppData(key, AppDataPatch(setRating = true, rating = 5))

        repo.flushPending("acc1")

        assertTrue(db.pendingEditDao().forAccount("acc1").isEmpty())
        assertEquals(2, dispatcher.requests.size)
        val metadata = dispatcher.requests.first { it.path!!.contains("/metadata") }
        assertEquals("""{"title":"New"}""", metadata.body.readUtf8())
    }

    @Test
    fun transientFailureKeepsTheEditAndCountsAttempts() = runBlocking {
        dispatcher.on("PATCH", "/metadata") { ocsError(503, "maintenance") }
        repo.editMetadata(key, MetadataPatch(mapOf("title" to JsonPrimitive("New"))))
        repo.flushPending("acc1")
        val row = db.pendingEditDao().forAccount("acc1").single()
        assertEquals(1, row.attempts)
        assertEquals("maintenance", row.lastError)
    }

    @Test
    fun permanentRejectionDropsTheEditReportsItAndRestoresServerState() = runBlocking {
        dispatcher.on("PATCH", "/metadata") { ocsError(403, "read-only share") }
        dispatcher.on("GET", "/books/1") { ocs(bookJson(1, title = "Server title")) }
        repo.editMetadata(key, MetadataPatch(mapOf("title" to JsonPrimitive("Mine"))))
        assertEquals("Mine", db.bookDao().get("acc1", 1)!!.title)

        repo.flushPending("acc1")

        assertTrue(db.pendingEditDao().forAccount("acc1").isEmpty())
        assertEquals("Server title", db.bookDao().get("acc1", 1)!!.title)
    }

    @Test
    fun rejectionIsReportedThroughFailures() = runBlocking {
        dispatcher.on("PATCH", "/metadata") { ocsError(400, "invalid isbn") }
        dispatcher.on("GET", "/books/1") { MockResponse().setResponseCode(500) }
        repo.editMetadata(key, MetadataPatch(mapOf("isbn" to JsonPrimitive("x"))))
        val failure = async(start = CoroutineStart.UNDISPATCHED) { repo.failures.first() }
        repo.flushPending("acc1")
        val f = withTimeout(3000) { failure.await() }
        assertEquals(key, f.key)
        assertEquals("invalid isbn", f.message)
    }
}
