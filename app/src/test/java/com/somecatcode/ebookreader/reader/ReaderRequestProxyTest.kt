package com.somecatcode.ebookreader.reader

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReaderRequestProxyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val origin = "https://appassets.androidplatform.net"
    private lateinit var file: File
    private val content = ByteArray(1000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        file = tmp.newFile("book.epub").also { it.writeBytes(content) }
    }

    private class FakeBackend(val respond: (ReaderResource, String?) -> BackendResponse) : ReaderBackend {
        val calls = mutableListOf<Pair<ReaderResource, String?>>()
        override fun fetch(resource: ReaderResource, range: String?): BackendResponse {
            calls += resource to range
            return respond(resource, range)
        }
    }

    private fun offlineProxy(local: File? = file) = ReaderRequestProxyImpl({ _, _ -> null }, { _, _ -> local })
        .also { it.bind("acc", 7, online = false) }

    private fun onlineProxy(backend: ReaderBackend?) = ReaderRequestProxyImpl({ a, f -> backend.takeIf { a == "acc" && f == 7L } }, { _, _ -> null })
        .also { it.bind("acc", 7, online = true) }

    private fun ReaderRequestProxyImpl.get(path: String, range: String? = null): WebResourceResponse? =
        handle("GET", Uri.parse(origin + path), range)

    private fun WebResourceResponse.bytes() = data.readBytes()

    // ---- offline -------------------------------------------------------------------------------

    @Test
    fun offlineServesTheWholeFileWithRangeSupport() {
        val r = offlineProxy().get("/api/book")!!
        assertEquals(200, r.statusCode)
        assertEquals("application/epub+zip", r.mimeType)
        assertEquals("bytes", r.responseHeaders["Accept-Ranges"])
        assertEquals("1000", r.responseHeaders["Content-Length"])
        assertTrue(content.contentEquals(r.bytes()))
    }

    @Test
    fun offlineRangeVariants() {
        val p = offlineProxy()
        p.get("/api/book", "bytes=10-19")!!.also {
            assertEquals(206, it.statusCode)
            assertEquals("bytes 10-19/1000", it.responseHeaders["Content-Range"])
            assertEquals("10", it.responseHeaders["Content-Length"])
            assertTrue(content.copyOfRange(10, 20).contentEquals(it.bytes()))
        }
        p.get("/api/book", "bytes=990-")!!.also {
            assertEquals("bytes 990-999/1000", it.responseHeaders["Content-Range"])
            assertTrue(content.copyOfRange(990, 1000).contentEquals(it.bytes()))
        }
        p.get("/api/book", "bytes=-5")!!.also {
            assertEquals("bytes 995-999/1000", it.responseHeaders["Content-Range"])
            assertTrue(content.copyOfRange(995, 1000).contentEquals(it.bytes()))
        }
        p.get("/api/book", "bytes=900-5000")!!.also {
            assertEquals("bytes 900-999/1000", it.responseHeaders["Content-Range"])
        }
    }

    @Test
    fun offlineUnsatisfiableAndIgnoredRanges() {
        val p = offlineProxy()
        p.get("/api/book", "bytes=1000-1100")!!.also {
            assertEquals(416, it.statusCode)
            assertEquals("bytes */1000", it.responseHeaders["Content-Range"])
        }
        // multiple ranges and foreign units are ignored: whole file
        assertEquals(200, p.get("/api/book", "bytes=0-1,5-6")!!.statusCode)
        assertEquals(200, p.get("/api/book", "items=0-1")!!.statusCode)
        assertEquals(200, p.get("/api/book", "bytes=9-3")!!.statusCode)
    }

    @Test
    fun offlineOnlyKnowsTheBookAndNeedsTheFile() {
        assertEquals(404, offlineProxy().get("/api/archive/entries")!!.statusCode)
        assertEquals(404, offlineProxy().get("/api/comic/page/0?w=500")!!.statusCode)
        assertEquals(404, offlineProxy(local = null).get("/api/book")!!.statusCode)
        assertEquals(404, offlineProxy(File(tmp.root, "missing.epub")).get("/api/book")!!.statusCode)
    }

    @Test
    fun mimeTypesByExtension() {
        assertEquals("application/vnd.comicbook+zip", ReaderRequestProxyImpl.mimeFor("a.CBZ"))
        assertEquals("application/x-mobipocket-ebook", ReaderRequestProxyImpl.mimeFor("a.mobi"))
        assertEquals("application/octet-stream", ReaderRequestProxyImpl.mimeFor("noext"))
    }

    // ---- routing ------------------------------------------------------------------------------

    @Test
    fun foreignHostsAreForbiddenAndReaderAssetsFallThrough() {
        val p = offlineProxy()
        assertEquals(403, p.handle("GET", Uri.parse("https://evil.example/api/book"), null)!!.statusCode)
        assertEquals(403, p.handle("GET", Uri.parse("http://appassets.androidplatform.net/api/book"), null)!!.statusCode)
        assertEquals(403, p.handle("GET", Uri.parse("https://appassets.androidplatform.net.evil.example/api/book"), null)!!.statusCode)
        assertNull(p.get("/reader/index.html"))
        assertNull(p.get("/reader/assets/a.js"))
    }

    @Test
    fun unknownPathsAndMethods() {
        val p = offlineProxy()
        assertEquals(404, p.get("/api/unknown")!!.statusCode)
        assertEquals(404, p.get("/other")!!.statusCode)
        assertEquals(404, p.get("/api/item")!!.statusCode)
        assertEquals(404, p.get("/api/comic/page/x")!!.statusCode)
        assertEquals(405, p.handle("POST", Uri.parse("$origin/api/book"), null)!!.statusCode)
        val unbound = ReaderRequestProxyImpl({ _, _ -> null }, { _, _ -> file })
        assertEquals(404, unbound.get("/api/book")!!.statusCode)
    }

    // ---- online -------------------------------------------------------------------------------

    @Test
    fun onlineForwardsRangeAndHeadersOfTheBook() {
        val backend = FakeBackend { _, _ ->
            BackendResponse(206, mapOf("content-type" to "application/epub+zip", "content-range" to "bytes 0-3/10", "content-length" to "4", "accept-ranges" to "bytes", "etag" to "\"x\"", "set-cookie" to "a=b"), "abcd".byteInputStream())
        }
        val r = onlineProxy(backend).get("/api/book", "bytes=0-3")!!
        assertEquals(listOf(ReaderResource.Book to "bytes=0-3"), backend.calls)
        assertEquals(206, r.statusCode)
        assertEquals("application/epub+zip", r.mimeType)
        assertEquals("bytes 0-3/10", r.responseHeaders["Content-Range"])
        assertEquals("\"x\"", r.responseHeaders["Etag"])
        assertTrue(r.responseHeaders.keys.none { it.equals("set-cookie", true) })
        assertEquals("abcd", String(r.bytes()))
    }

    @Test
    fun onlineMapsPathsToResources() {
        val backend = FakeBackend { _, _ -> BackendResponse(200, mapOf("content-type" to "application/json; charset=utf-8"), "{}".byteInputStream()) }
        val p = onlineProxy(backend)
        p.get("/api/archive/entries")
        p.get("/api/item?id=OEBPS%2Fa%20b.xhtml", "bytes=0-1")
        p.get("/api/comic/pages")
        p.get("/api/comic/page/12?w=720")
        p.get("/api/comic/page/3")
        assertEquals(
            listOf(
                ReaderResource.ArchiveEntries to null,
                ReaderResource.Item("OEBPS/a b.xhtml") to null, // Range only for the whole file
                ReaderResource.ComicPages to null,
                ReaderResource.ComicPage(12, 720) to null,
                ReaderResource.ComicPage(3, 0) to null,
            ),
            backend.calls,
        )
        val r = p.get("/api/archive/entries")!!
        assertEquals("application/json", r.mimeType)
        assertEquals("utf-8", r.encoding)
    }

    @Test
    fun onlineErrorMapping() {
        fun status(code: Int) = onlineProxy(FakeBackend { _, _ -> BackendResponse(code) }).get("/api/book")!!
        status(401).also {
            assertEquals(401, it.statusCode)
            assertEquals("unauthorized", it.responseHeaders["X-Reader-Error"])
        }
        assertEquals(404, status(404).statusCode)
        assertEquals(403, status(403).statusCode)
        assertEquals(500, status(500).statusCode)
        assertEquals(502, status(302).statusCode)
        assertEquals(416, status(416).statusCode)
        assertNull(status(404).responseHeaders["X-Reader-Error"])

        val net = onlineProxy(FakeBackend { _, _ -> throw IOException("down") }).get("/api/book")!!
        assertEquals(504, net.statusCode)
        assertEquals("network", net.responseHeaders["X-Reader-Error"])
    }

    @Test
    fun onlineWithoutBackendIsUnauthorized() {
        val r = onlineProxy(null).get("/api/book")!!
        assertEquals(401, r.statusCode)
        assertEquals("unauthorized", r.responseHeaders["X-Reader-Error"])
    }

    @Test
    fun rangeParser() {
        val parse = { h: String?, len: Long -> ReaderRequestProxyImpl.parseRange(h, len) }
        assertEquals(ReaderRequestProxyImpl.RangeResult.None, parse(null, 10))
        assertEquals(ReaderRequestProxyImpl.RangeResult.Satisfiable(0, 9), parse("bytes=0-", 10))
        assertEquals(ReaderRequestProxyImpl.RangeResult.Satisfiable(0, 9), parse("bytes=-100", 10))
        assertEquals(ReaderRequestProxyImpl.RangeResult.Unsatisfiable, parse("bytes=-0", 10))
        assertEquals(ReaderRequestProxyImpl.RangeResult.Unsatisfiable, parse("bytes=0-0", 0))
        assertEquals(ReaderRequestProxyImpl.RangeResult.None, parse("bytes=abc", 10))
    }
}
