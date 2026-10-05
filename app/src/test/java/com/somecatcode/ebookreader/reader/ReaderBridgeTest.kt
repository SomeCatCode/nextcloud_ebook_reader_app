package com.somecatcode.ebookreader.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.somecatcode.ebookreader.data.api.Locations
import com.somecatcode.ebookreader.data.api.Locator
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReaderBridgeTest {

    private val locator = Locator("OEBPS/c1.xhtml", "text/html", "One", Locations(progression = 0.25, totalProgression = 0.1, position = 3, cfi = "epubcfi(/6/2!/4)"))

    private fun roundTrip(m: HostToReader): JsonObject {
        val json = BridgeJson.encodeToString<HostToReader>(m)
        assertEquals(m, BridgeJson.decodeFromString<HostToReader>(json))
        return BridgeJson.parseToJsonElement(json).jsonObject
    }

    private fun roundTrip(m: ReaderToHost): JsonObject {
        val json = BridgeJson.encodeToString<ReaderToHost>(m)
        assertEquals(m, BridgeJson.decodeFromString<ReaderToHost>(json))
        return BridgeJson.parseToJsonElement(json).jsonObject
    }

    @Test
    fun hostMessagesRoundTripWithDiscriminator() {
        val book = BookRef("acc", 5, "epub", "Title")
        val open = roundTrip(HostToReader.Open(book, BookSource.File(fileName = "a.epub"), locator, ReaderSettings(theme = "sepia", einkMode = true)))
        assertEquals("open", open["type"]!!.jsonPrimitive.content)
        assertEquals("file", open["source"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sepia", open["settings"]!!.jsonObject["theme"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), open["settings"]!!.jsonObject["einkMode"])
        assertEquals("OEBPS/c1.xhtml", open["initialLocator"]!!.jsonObject["href"]!!.jsonPrimitive.content)

        roundTrip(HostToReader.Open(book, BookSource.RemoteZip(name = "a.epub"), null))
        val comic = roundTrip(HostToReader.Open(book, BookSource.RemoteComic(name = "c.cbz"), null))
        assertTrue(comic["source"]!!.jsonObject["pageUrl"]!!.jsonPrimitive.content.contains("{index}"))

        assertEquals("goTo", roundTrip(HostToReader.GoTo(locator = locator))["type"]!!.jsonPrimitive.content)
        assertEquals("c.xhtml#a", roundTrip(HostToReader.GoTo(href = "c.xhtml#a"))["href"]!!.jsonPrimitive.content)
        assertEquals("next", roundTrip(HostToReader.Next)["type"]!!.jsonPrimitive.content)
        assertEquals("prev", roundTrip(HostToReader.Prev)["type"]!!.jsonPrimitive.content)
        assertEquals("destroy", roundTrip(HostToReader.Destroy)["type"]!!.jsonPrimitive.content)
        val set = roundTrip(HostToReader.SetSettings(ReaderSettings(fontSize = 130, flow = "scrolled", comicRtl = true)))
        assertEquals("setSettings", set["type"]!!.jsonPrimitive.content)
        assertEquals(130, set["settings"]!!.jsonObject["fontSize"]!!.jsonPrimitive.content.toInt())
        // comic fit mode goes to reader-core as ReaderLayout.comicZoom
        assertEquals("fit-page", set["settings"]!!.jsonObject["comicZoom"]!!.jsonPrimitive.content)
        val fitWidth = roundTrip(HostToReader.SetSettings(ReaderSettings(comicZoom = "fit-width")))
        assertEquals("fit-width", fitWidth["settings"]!!.jsonObject["comicZoom"]!!.jsonPrimitive.content)
    }

    @Test
    fun pageMessagesRoundTrip() {
        assertEquals("ready", roundTrip(ReaderToHost.Ready())["type"]!!.jsonPrimitive.content)
        roundTrip(ReaderToHost.Opened(BookInfo("T", listOf("A", "B"), "de", isComic = false, fixedLayout = false, rtl = true, pageCount = 12)))
        roundTrip(ReaderToHost.Relocate(locator, 0.4, "Chapter", PageInfo(3, 100)))
        roundTrip(ReaderToHost.Toc(listOf(TocItem("A", "a.xhtml", listOf(TocItem("A1", "a.xhtml#1"))), TocItem("B", "b.xhtml"))))
        roundTrip(ReaderToHost.ExternalLink("https://example.org"))
        roundTrip(ReaderToHost.Tap("center"))
        for (code in ErrorCode.entries) {
            roundTrip(ReaderToHost.Error(code, "m"))
        }
    }

    @Test
    fun decodesWhatTheJsSideSends() {
        // exact shape of reader-web/src/host.ts (undefined fields dropped, subitems always present)
        val relocate = BridgeJson.decodeFromString<ReaderToHost>(
            """{"type":"relocate","locator":{"href":"c.xhtml","locations":{"progression":0.5,"totalProgression":0.2,"position":2}},"percentage":0.2,"page":{"current":2,"total":9},"unknown":1}""",
        ) as ReaderToHost.Relocate
        assertEquals(2, relocate.locator.locations?.position)
        assertEquals(PageInfo(2, 9), relocate.page)
        val err = BridgeJson.decodeFromString<ReaderToHost>("""{"type":"error","code":"unsupported-format","message":"x"}""") as ReaderToHost.Error
        assertEquals(ErrorCode.UNSUPPORTED_FORMAT, err.code)
        val opened = BridgeJson.decodeFromString<ReaderToHost>("""{"type":"opened","info":{"authors":[],"isComic":true,"fixedLayout":true,"rtl":false,"pageCount":10}}""") as ReaderToHost.Opened
        assertTrue(opened.info.isComic)
    }

    // ---- ReaderHostImpl -----------------------------------------------------------------------

    private val noProxy = object : ReaderRequestProxy {
        override fun bind(accountId: String, fileId: Long, online: Boolean) = Unit
        override fun intercept(request: android.webkit.WebResourceRequest) = null
    }

    private fun newHost() = ReaderHostImpl(noProxy, runOnMain = { it.run() })

    private val openMsg = HostToReader.Open(BookRef("a", 1, "epub"), BookSource.File(fileName = "a.epub"))

    @Test
    fun messagesAreQueuedUntilReadyAndThenEvaluated() {
        val host = newHost()
        val js = mutableListOf<String>()
        host.attach { js += it }
        host.send(openMsg)
        host.send(HostToReader.Next)
        assertTrue(js.isEmpty())
        host.onPageMessage("""{"type":"ready","protocol":1}""")
        assertEquals(2, js.size)
        assertTrue(js[0].startsWith("EbookReaderHost.receive({") && js[0].contains("\"type\":\"open\""))
        assertTrue(js[1].endsWith("""{"type":"next"})"""))
        host.send(HostToReader.Prev)
        assertEquals(3, js.size)
    }

    @Test
    fun pageEventsReachFlowsAndStates() {
        val host = newHost()
        host.attach { }
        host.onPageMessage("""{"type":"ready","protocol":1}""")
        host.onPageMessage("""{"type":"opened","info":{"title":"T","authors":["A"],"isComic":false,"fixedLayout":false,"rtl":false,"pageCount":3}}""")
        host.onPageMessage("""{"type":"toc","items":[{"label":"A","href":"a","subitems":[]}]}""")
        host.onPageMessage("""{"type":"relocate","locator":{"href":"a"},"percentage":0.5}""")
        assertEquals("T", host.bookInfo.value?.title)
        assertEquals(1, host.toc.value.size)
        assertEquals(0.5, host.lastRelocate.value?.percentage ?: 0.0, 0.0)
        host.send(openMsg) // a new open clears the book state
        assertEquals(null, host.bookInfo.value)
        assertTrue(host.toc.value.isEmpty())
    }

    @Test
    fun malformedMessagesAndUnsafeLinksAreContained() = runTest {
        val host = newHost()
        val seen = mutableListOf<ReaderToHost>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.events.collect { seen += it } }
        host.onPageMessage("not json")
        host.onPageMessage("""{"type":"nope"}""")
        host.onPageMessage(null)
        host.onPageMessage("""{"type":"externalLink","url":"javascript:alert(1)"}""")
        host.onPageMessage("""{"type":"externalLink","url":"https://example.org"}""")
        host.onNavigationBlocked("intent://evil")
        host.onNavigationBlocked("mailto:a@b.c")
        assertEquals(3, seen.count { it is ReaderToHost.Error })
        assertEquals(listOf("https://example.org", "mailto:a@b.c"), seen.filterIsInstance<ReaderToHost.ExternalLink>().map { it.url })
    }

    @Test
    fun reattachingReopensAtTheLastPosition() {
        val host = newHost()
        val first = mutableListOf<String>()
        host.attach { first += it }
        host.send(openMsg)
        host.onPageMessage("""{"type":"ready","protocol":1}""")
        host.onPageMessage("""{"type":"relocate","locator":{"href":"c9"},"percentage":0.9}""")
        host.send(HostToReader.SetSettings(ReaderSettings(theme = "dark")))
        val second = mutableListOf<String>()
        host.attach { second += it }
        host.onPageMessage("""{"type":"ready","protocol":1}""")
        assertEquals(1, second.size)
        assertTrue(second[0].contains("\"href\":\"c9\"") && second[0].contains("\"theme\":\"dark\""))
    }

    @Test
    fun destroySendsDestroyOnceAndStopsSending() {
        val host = newHost()
        val js = mutableListOf<String>()
        host.attach { js += it }
        host.onPageMessage("""{"type":"ready","protocol":1}""")
        host.destroy()
        host.send(HostToReader.Next)
        assertEquals(1, js.size)
        assertTrue(js[0].contains("destroy"))
    }

    // ---- annotations --------------------------------------------------------------------------

    @Test
    fun annotationMessagesRoundTrip() {
        val set = roundTrip(HostToReader.SetAnnotations(listOf(DrawnAnnotation("u1", "epubcfi(/6/4!/4/2,/1:0,/1:5)", "green", hasNote = true))))
        assertEquals("setAnnotations", set["type"]!!.jsonPrimitive.content)
        val first = set["annotations"]!!.jsonArray[0].jsonObject
        assertEquals("u1", first["id"]!!.jsonPrimitive.content)
        assertEquals("green", first["color"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), first["hasNote"])
        // no color = the key is left out (reader-core draws yellow)
        val plain = roundTrip(HostToReader.SetAnnotations(listOf(DrawnAnnotation("u2", "epubcfi(/6/2!/4)"))))
        assertFalse(plain["annotations"]!!.jsonArray[0].jsonObject.containsKey("color"))
        assertEquals("""{"type":"clearSelection"}""", BridgeJson.encodeToString<HostToReader>(HostToReader.ClearSelection))

        val rect = SelectionRect(1.0, 2.0, 3.0, 4.0)
        roundTrip(ReaderToHost.Selection("Hello", "epubcfi(/6/4!/4/2,/1:0,/1:5)", locator, rect))
        roundTrip(ReaderToHost.SelectionClear)
        roundTrip(ReaderToHost.AnnotationClick("u1", rect))
    }

    @Test
    fun decodesTheAnnotationMessagesOfTheJsSide() {
        // exact shapes of reader-web/src/host.ts
        val sel = BridgeJson.decodeFromString<ReaderToHost>(
            """{"type":"selection","text":"Hello world","cfi":"epubcfi(/6/4!/4/2,/1:0,/1:11)","locator":{"href":"c1.xhtml","title":"One","locations":{"cfi":"epubcfi(/6/4!/4/2,/1:0,/1:11)","progression":0.1,"totalProgression":0.05}},"rect":{"left":10.5,"top":20,"right":200,"bottom":44}}""",
        ) as ReaderToHost.Selection
        assertEquals("Hello world", sel.text)
        assertEquals("epubcfi(/6/4!/4/2,/1:0,/1:11)", sel.locator.locations?.cfi)
        assertEquals(10.5, sel.rect.left, 0.0)
        assertEquals(ReaderToHost.SelectionClear, BridgeJson.decodeFromString<ReaderToHost>("""{"type":"selectionClear"}"""))
        val click = BridgeJson.decodeFromString<ReaderToHost>("""{"type":"annotationClick","id":"u1","rect":{"left":0,"top":0,"right":1,"bottom":1}}""") as ReaderToHost.AnnotationClick
        assertEquals("u1", click.id)
        val opened = BridgeJson.decodeFromString<ReaderToHost>(
            """{"type":"opened","info":{"authors":[],"isComic":false,"fixedLayout":false,"rtl":false,"pageCount":3,"supportsAnnotations":true}}""",
        ) as ReaderToHost.Opened
        assertTrue(opened.info.supportsAnnotations)
        // older bundles without the flag: no annotations
        val old = BridgeJson.decodeFromString<ReaderToHost>("""{"type":"opened","info":{"authors":[],"isComic":false,"fixedLayout":false,"rtl":false,"pageCount":3}}""") as ReaderToHost.Opened
        assertFalse(old.info.supportsAnnotations)
    }

    @Test
    fun reattachingRedrawsTheLatestHighlightsAfterTheReopen() {
        val host = newHost()
        host.attach { }
        host.send(openMsg)
        host.onPageMessage("""{"type":"ready","protocol":1}""")
        host.send(HostToReader.SetAnnotations(listOf(DrawnAnnotation("old", "epubcfi(/6/2!/4)"))))
        host.send(HostToReader.SetAnnotations(listOf(DrawnAnnotation("new", "epubcfi(/6/2!/6)"))))
        val second = mutableListOf<String>()
        host.attach { second += it }
        host.onPageMessage("""{"type":"ready","protocol":1}""")
        assertEquals(2, second.size)
        assertTrue(second[0].contains("\"type\":\"open\""))
        assertTrue(second[1].contains("\"type\":\"setAnnotations\"") && second[1].contains("\"new\"") && !second[1].contains("\"old\""))
    }

    @Test
    fun queuedHighlightsKeepOnlyTheLatestListAndSelectionActionsAreForwarded() = runTest {
        val host = newHost()
        val js = mutableListOf<String>()
        host.attach { js += it }
        host.send(openMsg)
        host.send(HostToReader.SetAnnotations(emptyList()))
        host.send(HostToReader.SetAnnotations(listOf(DrawnAnnotation("u", "epubcfi(/6/2!/4)"))))
        host.onPageMessage("""{"type":"ready","protocol":1}""")
        assertEquals(2, js.size)
        assertTrue(js[1].contains("\"u\""))

        assertFalse(host.supportsAnnotations)
        host.onPageMessage("""{"type":"opened","info":{"authors":[],"isComic":false,"fixedLayout":false,"rtl":false,"pageCount":3,"supportsAnnotations":true}}""")
        assertTrue(host.supportsAnnotations)
        val actions = mutableListOf<SelectionAction>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.selectionActions.collect { actions += it } }
        host.onSelectionAction(SelectionAction.HIGHLIGHT)
        host.onSelectionAction(SelectionAction.NOTE)
        assertEquals(listOf(SelectionAction.HIGHLIGHT, SelectionAction.NOTE), actions)
    }

    @Test
    fun safeExternalUrls() {
        assertTrue(isSafeExternalUrl("https://a.b"))
        assertTrue(isSafeExternalUrl("HTTP://a.b"))
        assertTrue(isSafeExternalUrl("mailto:x@y.z"))
        assertFalse(isSafeExternalUrl("javascript:alert(1)"))
        assertFalse(isSafeExternalUrl("file:///etc/passwd"))
        assertFalse(isSafeExternalUrl("intent://x"))
        assertFalse(isSafeExternalUrl("nocolon"))
    }
}
