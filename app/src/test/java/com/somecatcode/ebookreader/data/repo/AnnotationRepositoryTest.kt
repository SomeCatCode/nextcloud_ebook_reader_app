package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.DbTest
import com.somecatcode.ebookreader.data.RecordingScheduler
import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.StaticApiFactory
import com.somecatcode.ebookreader.data.UUID_A
import com.somecatcode.ebookreader.data.UUID_B
import com.somecatcode.ebookreader.data.annotationJson
import com.somecatcode.ebookreader.data.api.AnnotationDto
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.Locations
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.ocs
import com.somecatcode.ebookreader.data.ocsError
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnnotationRepositoryTest : DbTest() {

    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)
    private val scheduler = RecordingScheduler()
    private var now = 1_000L
    private val uuids = ArrayDeque(listOf(UUID_A, UUID_B))
    private lateinit var repo: AnnotationRepositoryImpl
    private val key = BookKey("acc1", 1)
    private val cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)"
    private val locator = Locator("c1.xhtml", title = "One", locations = Locations(cfi = cfi, totalProgression = 0.2))
    private val third = "00000000-0000-4000-8000-000000000003"

    @Before
    fun setUp() {
        repo = AnnotationRepositoryImpl(db, StaticApiFactory(server.ebookApi()), scheduler, clock = { now }, newUuid = { uuids.removeFirst().uppercase() })
        runBlocking { db.bookDao().upsertAll(listOf(book(1))) }
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun row(uuid: String = UUID_A) = db.annotationDao().get("acc1", uuid)

    private fun dto(json: String) = ApiJson.decodeFromString(AnnotationDto.serializer(), json)

    @Test
    fun createIsLocalFirstAndScheduled() = runBlocking {
        val uuid = repo.create(key, locator, "Hello", AnnotationColor.GREEN)
        assertEquals(UUID_A, uuid) // lower-case like the server
        val r = row()!!
        assertTrue(r.dirty)
        assertEquals("highlight", r.type)
        assertEquals("green", r.color)
        assertEquals(1_000L, r.clientUpdatedAt)
        assertEquals(listOf("acc1"), scheduler.scheduled)
        val shown = repo.annotations(key).first().single()
        assertEquals(AnnotationKind.HIGHLIGHT, shown.kind)
        assertEquals(cfi, shown.cfi)
        assertTrue(shown.pending)

        repo.create(key, locator, "x".repeat(3000), AnnotationColor.YELLOW, note = "My note")
        val note = row(UUID_B)!!
        assertEquals("note", note.type)
        assertEquals(ANNOTATION_MAX_TEXT, note.text!!.length)
    }

    @Test
    fun pushUpsertsAndStoresTheServerRow() = runBlocking {
        repo.create(key, locator, "Hello", AnnotationColor.BLUE, note = "n")
        dispatcher.on("POST", "/books/1/annotations") { ocs(annotationJson(color = "blue", note = "n", clientUpdatedAt = 1_000, updatedAt = 1_500)) }
        repo.pushDirty("acc1")
        val body = ApiJson.parseToJsonElement(dispatcher.requests.single().body.readUtf8()).jsonObject
        assertEquals(UUID_A, body["uuid"]!!.jsonPrimitive.content)
        assertEquals("note", body["type"]!!.jsonPrimitive.content)
        assertEquals("1000", body["clientUpdatedAt"].toString())
        assertEquals("1000", body["createdAt"].toString())
        assertEquals(cfi, body["locator"]!!.jsonObject["locations"]!!.jsonObject["cfi"]!!.jsonPrimitive.content)
        val r = row()!!
        assertFalse(r.dirty)
        assertEquals(1_500L, r.updatedAt)
    }

    @Test
    fun pushConflictTakesTheNewerServerVersion() = runBlocking {
        repo.create(key, locator, "Hello", AnnotationColor.YELLOW)
        dispatcher.on("POST", "/annotations") { ocs("""{"current":${annotationJson(color = "pink", clientUpdatedAt = 5_000)}}""", 409) }
        repo.pushDirty("acc1")
        val r = row()!!
        assertEquals("pink", r.color)
        assertFalse(r.dirty)
    }

    @Test
    fun changesAndDeletesBumpTheClockAndAreUploaded() = runBlocking {
        repo.create(key, locator, "Hello", AnnotationColor.YELLOW)
        now = 500 // clock went backwards: the row's clock still moves forward
        repo.setColor(key, UUID_A, AnnotationColor.PURPLE)
        assertEquals(1_001L, row()!!.clientUpdatedAt)
        repo.setNote(key, UUID_A, "  ")
        assertNull(row()!!.note)
        repo.delete(key, UUID_A)
        assertTrue(row()!!.deleted)
        assertTrue(repo.annotations(key).first().isEmpty())

        dispatcher.on("DELETE", "/annotations/$UUID_A") { ocs(annotationJson(deleted = true, clientUpdatedAt = 1_003)) }
        repo.pushDirty("acc1")
        assertEquals("1003", dispatcher.requests.single().requestUrl!!.queryParameter("clientUpdatedAt"))
        assertNull(row())
    }

    @Test
    fun deleteOfANeverUploadedAnnotationIsPurgedOn404() = runBlocking {
        repo.create(key, locator, "Hello", AnnotationColor.YELLOW)
        repo.delete(key, UUID_A)
        dispatcher.on("DELETE", "/annotations/") { ocsError(404, "Annotation not found") }
        repo.pushDirty("acc1")
        assertNull(row())
    }

    @Test
    fun transientErrorsKeepRowsDirtyAndPermanentOnesStopRetrying() = runBlocking {
        repo.create(key, locator, "Hello", AnnotationColor.YELLOW)
        dispatcher.on("POST", "/annotations") { MockResponse().setResponseCode(503).setBody("{}") }
        repo.pushDirty("acc1")
        assertTrue(row()!!.dirty)

        dispatcher.on("POST", "/annotations") { ocsError(404, "File not found") }
        repo.pushDirty("acc1") // book known locally: maybe a server without annotations, retry later
        assertTrue(row()!!.dirty)

        dispatcher.on("POST", "/annotations") { ocsError(400, "too many annotations for this book") }
        repo.pushDirty("acc1")
        val r = row()!!
        assertFalse(r.dirty) // kept on this device, not retried forever
        assertFalse(r.deleted)
    }

    @Test
    fun annotationsOfABookThatIsGoneAreDroppedOn404() = runBlocking {
        repo.create(BookKey("acc1", 99), locator, "Hello", AnnotationColor.YELLOW)
        dispatcher.on("POST", "/annotations") { ocsError(404, "File not found") }
        repo.pushDirty("acc1")
        assertNull(row())
    }

    @Test
    fun aChangeDuringTheUploadIsNotOverwritten() = runBlocking {
        repo.create(key, locator, "Hello", AnnotationColor.YELLOW)
        dispatcher.on("POST", "/annotations") {
            runBlocking { repo.setNote(key, UUID_A, "typed meanwhile") }
            ocs(annotationJson(clientUpdatedAt = 1_000))
        }
        repo.pushDirty("acc1")
        val r = row()!!
        assertEquals("typed meanwhile", r.note)
        assertTrue(r.dirty)
    }

    @Test
    fun refreshMergesTheServerList() = runBlocking {
        // clean row deleted on the server, dirty local row newer than the server, new server row
        db.annotationDao().upsert(dto(annotationJson(uuid = UUID_B, clientUpdatedAt = 10)).toEntity("acc1"))
        repo.create(key, locator, "local", AnnotationColor.GREEN) // UUID_A, clientUpdatedAt 1000, dirty
        dispatcher.on("GET", "/books/1/annotations") {
            ocs("""{"annotations":[${annotationJson(uuid = UUID_A, color = "pink", clientUpdatedAt = 900)},${annotationJson(uuid = third, text = "web")}]}""")
        }
        repo.refresh(key)
        assertEquals("green", row()!!.color) // the local change is newer
        assertNull(row(UUID_B))
        assertNotNull(row(third))
    }

    @Test
    fun refreshOfflineKeepsTheLocalState() = runBlocking {
        repo.create(key, locator, "local", AnnotationColor.GREEN)
        server.shutdown()
        repo.refresh(key)
        assertNotNull(row())
    }

    @Test
    fun readingOrderFollowsTheCfi() {
        assertTrue(compareCfi("epubcfi(/6/4!/4/2,/1:0,/1:5)", "epubcfi(/6/4!/4/10,/1:0,/1:5)")!! < 0)
        assertTrue(compareCfi("epubcfi(/6/12!/4/2/1:3)", "epubcfi(/6/4!/4/2/1:3)")!! > 0)
        assertTrue(compareCfi("epubcfi(/6/4[ch1]!/4/2,/1:9,/1:20)", "epubcfi(/6/4[ch1]!/4/2,/1:2,/1:5)")!! > 0)
        assertEquals(0, compareCfi("epubcfi(/6/4!/4)", "epubcfi(/6/4!/4)"))
        assertNull(compareCfi("nope", "epubcfi(/6/4!/4)"))

        fun a(uuid: String, cfi: String?, total: Double?, created: Long) = BookAnnotation(
            key, uuid, AnnotationType.HIGHLIGHT, Locator("c", locations = Locations(cfi = cfi, totalProgression = total)),
            null, null, null, created, created, false,
        )
        val sorted = listOf(
            a("late", "epubcfi(/6/8!/4/2,/1:0,/1:1)", 0.1, 1),
            a("early", "epubcfi(/6/2!/4/2,/1:0,/1:1)", 0.9, 2),
        ).sortedWith(AnnotationOrder).map { it.uuid }
        assertEquals(listOf("early", "late"), sorted)
        val byProgress = listOf(a("b", null, 0.5, 1), a("a", null, 0.1, 2)).sortedWith(AnnotationOrder).map { it.uuid }
        assertEquals(listOf("a", "b"), byProgress)
        assertEquals("Hello", cut("Hello world", 5))
        assertEquals("ab", cut("ab😀", 3)) // no half emoji
    }
}
