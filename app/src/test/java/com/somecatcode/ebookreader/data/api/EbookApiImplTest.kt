package com.somecatcode.ebookreader.data.api

import com.somecatcode.ebookreader.data.RoutingDispatcher
import com.somecatcode.ebookreader.data.ebookApi
import com.somecatcode.ebookreader.data.bookJson
import com.somecatcode.ebookreader.data.ocs
import com.somecatcode.ebookreader.data.ocsError
import com.somecatcode.ebookreader.data.progressJson
import com.somecatcode.ebookreader.data.serverWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EbookApiImplTest {

    private val dispatcher = RoutingDispatcher()
    private val server: MockWebServer = serverWith(dispatcher)

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun unwrapsEnvelopeAndSendsAuthHeaders() = runBlocking {
        dispatcher.on("GET", "/books") { ocs("""{"books":[${bookJson(1)}],"total":1}""") }
        val result = server.ebookApi().books(BookQuery(search = "dune", include = listOf(FilterTerm(FilterType.GENRE, "Sci-Fi")), limit = 20))
        assertEquals(1, result.total)
        assertEquals(1L, result.books.single().fileId)

        val req = dispatcher.requests.single()
        assertEquals(Credentials.basic("alice", "secret-app-pw", Charsets.UTF_8), req.getHeader("Authorization"))
        assertEquals("true", req.getHeader("OCS-APIRequest"))
        assertTrue(req.getHeader("Accept")!!.contains("json"))
        val url = req.requestUrl!!
        assertEquals("/ocs/v2.php/apps/ebookreader/api/v1/books", url.encodedPath)
        assertEquals("json", url.queryParameter("format"))
        assertEquals("dune", url.queryParameter("search"))
        assertEquals(listOf("genre:Sci-Fi"), url.queryParameterValues("include[]"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test
    fun progressNullDataMeansNeverOpened() = runBlocking {
        dispatcher.on("GET", "/progress/5") { ocs("null") }
        assertNull(server.ebookApi().progress(5))
    }

    @Test
    fun mapsHttpErrors() = runBlocking {
        val cases = mapOf(
            401 to ApiException.Unauthorized::class.java,
            403 to ApiException.Forbidden::class.java,
            404 to ApiException.NotFound::class.java,
            400 to ApiException.BadRequest::class.java,
            422 to ApiException.BadRequest::class.java,
            500 to ApiException.Server::class.java,
            503 to ApiException.Server::class.java,
        )
        for ((code, type) in cases) {
            val d = RoutingDispatcher()
            d.on("GET", "/facets") { ocsError(code, "boom $code") }
            serverWith(d).use { s ->
                try {
                    s.ebookApi().facets()
                    fail("expected exception for $code")
                } catch (e: ApiException) {
                    assertEquals("code $code", type, e.javaClass)
                    assertTrue(e.message!!.contains("boom"))
                }
            }
        }
    }

    @Test
    fun rateLimitCarriesRetryAfter() = runBlocking {
        dispatcher.on("GET", "/facets") { ocsError(429, "slow down").addHeader("Retry-After", "17") }
        try {
            server.ebookApi().facets()
            fail()
        } catch (e: ApiException.RateLimited) {
            assertEquals(17L, e.retryAfterSeconds)
        }
    }

    @Test
    fun unparsableBodyIsServerError() = runBlocking {
        dispatcher.on("GET", "/facets") { MockResponse().setBody("<html>maintenance</html>") }
        try {
            server.ebookApi().facets()
            fail()
        } catch (e: ApiException.Server) {
            assertEquals(200, e.statusCode)
        }
    }

    @Test
    fun connectionFailureIsNetworkError() = runBlocking {
        val api = server.ebookApi()
        server.shutdown()
        try {
            api.facets()
            fail()
        } catch (e: ApiException.Network) {
            // expected
        }
    }

    @Test
    fun putProgressConflictIsAResultNotAnException() = runBlocking {
        dispatcher.on("PUT", "/progress/9") { ocs("""{"current":${progressJson(9, clientUpdatedAt = 500)}}""", 409) }
        val result = server.ebookApi().putProgress(
            9,
            ProgressPutRequest(Locator("c1.xhtml"), 0.1, "Pixel", clientUpdatedAt = 100),
        )
        assertTrue(result is ProgressPutResult.Conflict)
        assertEquals(500L, (result as ProgressPutResult.Conflict).current.clientUpdatedAt)

        val body = ApiJson.parseToJsonElement(dispatcher.requests.single().body.readUtf8()).jsonObject
        assertEquals("100", body["clientUpdatedAt"].toString())
        assertEquals("\"Pixel\"", body["device"].toString())
    }

    @Test
    fun putProgressStored() = runBlocking {
        dispatcher.on("PUT", "/progress/9") { ocs(progressJson(9, clientUpdatedAt = 100)) }
        val result = server.ebookApi().putProgress(9, ProgressPutRequest(Locator("c1.xhtml"), 0.5, null, 100))
        assertTrue(result is ProgressPutResult.Stored)
    }

    @Test
    fun progressBatchIsChunkedBy100() = runBlocking {
        dispatcher.on("POST", "/progress/batch") { req ->
            val count = ApiJson.parseToJsonElement(req.body.readUtf8()).jsonObject["items"]!!.let { (it as kotlinx.serialization.json.JsonArray).size }
            val results = (1..count).joinToString(",") { """{"fileId":$it,"status":"ok","progress":null}""" }
            ocs("""{"results":[$results]}""")
        }
        val items = (1L..150L).map { ProgressBatchItem(it, Locator("a"), 0.1, null, 1) }
        val results = server.ebookApi().putProgressBatch(items)
        assertEquals(2, dispatcher.requests.size)
        assertEquals(150 - 100 + 100, results.size)
    }

    @Test
    fun syncSendsCursorAndParsesPaging() = runBlocking {
        dispatcher.on("GET", "/sync") { req ->
            if (req.requestUrl!!.queryParameter("cursor") == "") {
                ocs("""{"books":[${bookJson(1)}],"deleted":[],"progress":[],"cursor":"c1","hasMore":true}""")
            } else {
                ocs("""{"books":[],"deleted":[7],"progress":[${progressJson(1)}],"cursor":"c2","hasMore":false}""")
            }
        }
        val api = server.ebookApi()
        val first = api.sync("")
        assertTrue(first.hasMore)
        assertEquals("c1", first.cursor)
        val second = api.sync(first.cursor)
        assertEquals(listOf(7L), second.deleted)
        assertEquals(1, second.progress.size)
        assertEquals("c1", dispatcher.requests[1].requestUrl!!.queryParameter("cursor"))
    }

    @Test
    fun invalidCursorIsBadRequest() = runBlocking {
        dispatcher.on("GET", "/sync") { ocsError(400, "Invalid cursor") }
        try {
            server.ebookApi().sync("garbage")
            fail()
        } catch (e: ApiException.BadRequest) {
            assertEquals("Invalid cursor", e.message)
        }
    }

    @Test
    fun compatibilityCheck() = runBlocking {
        // app missing
        val missing = RoutingDispatcher().apply {
            on("GET", "/cloud/capabilities") { ocs("""{"version":{"major":31,"minor":0,"micro":1,"string":"31.0.1"},"capabilities":{"core":{}}}""") }
        }
        serverWith(missing).use { assertEquals(ServerCompatibility.AppMissing, it.ebookApi().checkCompatibility()) }

        // too old: capabilities present, /series is 404
        val old = RoutingDispatcher().apply {
            on("GET", "/cloud/capabilities") { ocs("""{"capabilities":{"ebookreader":{"apiVersion":1,"apiStable":false,"formats":["epub"]}}}""") }
        }
        serverWith(old).use { assertEquals(ServerCompatibility.AppTooOld, it.ebookApi().checkCompatibility()) }

        // ok
        val ok = RoutingDispatcher().apply {
            on("GET", "/cloud/capabilities") { ocs("""{"version":{"string":"31.0.1"},"capabilities":{"ebookreader":{"apiVersion":1,"apiStable":true,"formats":["epub","cbz"]}}}""") }
            on("GET", "/series") { ocs("""{"series":[]}""") }
        }
        serverWith(ok).use {
            val result = it.ebookApi().checkCompatibility() as ServerCompatibility.Ok
            assertEquals("31.0.1", result.serverVersion)
            assertEquals(listOf("epub", "cbz"), result.capabilities.formats)
        }
    }

    @Test
    fun appDataPatchSendsExplicitNullToClearRating() = runBlocking {
        dispatcher.on("PATCH", "/app-data") { ocs(bookJson(3)) }
        server.ebookApi().patchAppData(3, AppDataPatch(setRating = true, rating = null, readStatus = ReadStatus.FINISHED))
        val body = ApiJson.parseToJsonElement(dispatcher.requests.single().body.readUtf8()).jsonObject
        assertEquals(JsonNull, body["rating"])
        assertEquals(JsonPrimitive("finished"), body["readStatus"])
    }

    @Test
    fun metadataPatchSendsOnlyGivenKeys() = runBlocking {
        dispatcher.on("PATCH", "/metadata") { ocs("""{"book":${bookJson(3)},"warnings":[],"writeQueued":true}""") }
        val result = server.ebookApi().patchMetadata(
            3,
            MetadataPatch(mapOf("title" to JsonPrimitive("New"), "series" to JsonNull, "genres" to buildJsonArray { add(JsonPrimitive("Fantasy")) })),
        )
        assertTrue(result.writeQueued)
        val body = ApiJson.parseToJsonElement(dispatcher.requests.single().body.readUtf8()).jsonObject
        assertEquals(setOf("title", "series", "genres"), body.keys)
    }

    @Test
    fun urlBuilders() {
        val api = server.ebookApi()
        val dav = api.davFileUrl("al ice", "/Books/Ärger & Co/Buch #1.epub")
        assertEquals("/remote.php/dav/files/al%20ice/Books/%C3%84rger%20&%20Co/Buch%20%231.epub", dav.encodedPath)
        assertEquals("/index.php/apps/ebookreader/cover/4", api.coverUrl(4, CoverSize.LARGE).encodedPath)
        assertEquals("large", api.coverUrl(4, CoverSize.LARGE).queryParameter("size"))
        assertEquals("300", api.comicPageUrl(4, 2, 300).queryParameter("w"))
        assertEquals("OEBPS/c 1.xhtml", api.itemUrl(4, "OEBPS/c 1.xhtml").queryParameter("id"))
    }

    @Test
    fun revokeSendsDeleteToCoreApppassword() = runBlocking {
        dispatcher.on("DELETE", "/ocs/v2.php/core/apppassword") { ocs("[]") }
        server.ebookApi().revokeAppPassword()
        assertEquals("DELETE", dispatcher.requests.single().method)
    }

    @Test
    fun authHeaderIsNotSentToOtherHosts() {
        val http = server.ebookApi().http
        val foreign = http.authorize(Request.Builder().url("https://evil.example.org/x").build())
        assertNull(foreign.header("Authorization"))
        val own = http.authorize(Request.Builder().url(server.url("/x")).build())
        assertTrue(own.header("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun crossHostRedirectIsRefused() {
        dispatcher.on("GET", "/dav") { MockResponse().setResponseCode(302).addHeader("Location", "https://evil.example.org/steal") }
        val http = server.ebookApi().http
        try {
            http.execute(Request.Builder().url(server.url("/dav")).build()).close()
            fail("redirect to another host must not be followed")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("another host"))
        }
        assertEquals(1, dispatcher.requests.size)
    }

    private inline fun <T : MockWebServer, R> T.use(block: (T) -> R): R = try {
        block(this)
    } finally {
        shutdown()
    }
}
