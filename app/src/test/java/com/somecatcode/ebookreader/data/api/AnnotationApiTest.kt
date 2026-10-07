package com.somecatcode.ebookreader.data.api

import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.UUID_A
import com.somecatcode.ebookreader.data.annotationJson
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.ocs
import com.somecatcode.ebookreader.data.ocsError
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `/books/{id}/annotations` and `/annotations/{uuid}` (server docs/CONTRACTS-v4.md section 4). */
class AnnotationApiTest {

    private val dispatcher = RoutingDispatcher()
    private val server = serverWith(dispatcher)

    @After
    fun tearDown() = server.shutdown()

    private fun body() = ApiJson.parseToJsonElement(dispatcher.requests.last().body.readUtf8()).jsonObject

    @Test
    fun listsTheAnnotationsOfABook() = runBlocking {
        dispatcher.on("GET", "/books/7/annotations") { ocs("""{"annotations":[${annotationJson(fileId = 7, note = "Mine")}]}""") }
        val list = server.ebookApi().annotations(7)
        assertEquals(UUID_A, list.single().uuid)
        assertEquals("Mine", list.single().note)
        assertEquals("epubcfi(/6/4!/4/2,/1:0,/1:5)", list.single().locator.locations?.cfi)
        assertEquals("/ocs/v2.php/apps/ebookreader/api/v1/books/7/annotations", dispatcher.requests.single().requestUrl!!.encodedPath)
    }

    @Test
    fun upsertSendsTheFullStateAndReturnsTheStoredRow() = runBlocking {
        dispatcher.on("POST", "/books/7/annotations") { ocs(annotationJson(fileId = 7, clientUpdatedAt = 300)) }
        val request = AnnotationUpsertRequest(
            uuid = UUID_A, type = "highlight", locator = Locator("c1.xhtml", locations = Locations(cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)")),
            text = "Hello", note = null, color = "green", clientUpdatedAt = 300, createdAt = 250,
        )
        val result = server.ebookApi().upsertAnnotation(7, request)
        assertEquals(300L, (result as AnnotationWriteResult.Stored).annotation.clientUpdatedAt)
        val b = body()
        assertEquals(UUID_A, b["uuid"]!!.jsonPrimitive.content)
        assertEquals("green", b["color"]!!.jsonPrimitive.content)
        assertEquals("250", b["createdAt"].toString())
        assertFalse("null fields are left out (the server treats them as empty)", b.containsKey("note"))
    }

    @Test
    fun conflictsAreResultsForAllWrites() = runBlocking {
        val current = annotationJson(clientUpdatedAt = 900, note = "newer")
        dispatcher.on("POST", "/annotations") { ocs("""{"current":$current}""", 409) }
        dispatcher.on("PATCH", "/annotations/") { ocs("""{"current":$current}""", 409) }
        dispatcher.on("DELETE", "/annotations/") { ocs("""{"current":$current}""", 409) }
        val api = server.ebookApi()
        val req = AnnotationUpsertRequest(UUID_A, "highlight", Locator("c"), clientUpdatedAt = 1)
        assertEquals("newer", (api.upsertAnnotation(1, req) as AnnotationWriteResult.Conflict).current.note)
        assertEquals(900L, (api.patchAnnotation(UUID_A, AnnotationPatchRequest(note = "x", clientUpdatedAt = 1)) as AnnotationWriteResult.Conflict).current.clientUpdatedAt)
        assertTrue(api.deleteAnnotation(UUID_A, 1) is AnnotationWriteResult.Conflict)
    }

    @Test
    fun patchSendsOnlyTheGivenFieldsAndDeleteTheClock() = runBlocking {
        dispatcher.on("PATCH", "/annotations/$UUID_A") { ocs(annotationJson(note = null, color = null)) }
        dispatcher.on("DELETE", "/annotations/$UUID_A") { ocs(annotationJson(deleted = true, clientUpdatedAt = 77)) }
        val api = server.ebookApi()
        api.patchAnnotation(UUID_A.uppercase(), AnnotationPatchRequest(note = "", clientUpdatedAt = 5))
        val patch = body()
        assertEquals(setOf("note", "clientUpdatedAt"), patch.keys)
        assertEquals("", patch["note"]!!.jsonPrimitive.content)
        assertEquals("/ocs/v2.php/apps/ebookreader/api/v1/annotations/$UUID_A", dispatcher.requests.last().requestUrl!!.encodedPath)

        val deleted = api.deleteAnnotation(UUID_A, 77) as AnnotationWriteResult.Stored
        assertTrue(deleted.annotation.deleted)
        assertEquals("77", dispatcher.requests.last().requestUrl!!.queryParameter("clientUpdatedAt"))
    }

    @Test
    fun invalidIdsNeverReachThePathAndErrorsAreMapped() = runBlocking {
        val api = server.ebookApi()
        try {
            api.deleteAnnotation("../books/1", 1)
            fail()
        } catch (e: ApiException.BadRequest) {
            assertTrue(dispatcher.requests.isEmpty())
        }
        dispatcher.on("PATCH", "/annotations/") { ocsError(404, "Annotation not found") }
        try {
            api.patchAnnotation(UUID_A, AnnotationPatchRequest(note = "x"))
            fail()
        } catch (e: ApiException.NotFound) {
            // expected
        }
    }

    @Test
    fun syncPagesCarryAnnotationsWithTombstones() {
        val dto = ApiJson.decodeFromString(
            SyncDto.serializer(),
            """{"books":[],"deleted":[],"progress":[],"annotations":[${annotationJson(deleted = true)}],"cursor":"x","hasMore":false}""",
        )
        assertTrue(dto.annotations.single().deleted)
        val caps = ApiJson.decodeFromString(EbookReaderCapabilities.serializer(), """{"apiVersion":1,"annotations":true}""")
        assertTrue(caps.annotations)
    }
}
