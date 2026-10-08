package com.somecatcode.ebookreader.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses a server-shaped OCS `/sync` response (including unknown fields) with [ApiJson]. */
class DtoParsingTest {

    private val syncJson = """
        {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":{
          "books":[{"fileId":12,"format":"epub","path":"/Books/a.epub","size":1000,"title":"A","authors":["X","Y"],
                    "series":"S","seriesIndex":1.5,"description":null,"language":"de","publisher":null,"isbn":null,
                    "publishedAt":"2020","genres":["Fantasy"],"tags":[],"rating":4,"readStatus":"reading",
                    "hasCover":true,"coverEtag":"abc","mtime":1,"addedAt":2,"updatedAt":3,"editable":true,
                    "downloadable":true,"overrides":["title"],"hasSidecar":false,"somethingNew":1,
                    "progress":{"fileId":12,"locator":{"href":"c1.xhtml","locations":{"progression":0.5,"cfi":"epubcfi(/6/2)"}},
                                "percentage":0.25,"device":null,"clientUpdatedAt":10,"updatedAt":11}}],
          "deleted":[7],"progress":[],"cursor":"abc==","hasMore":false}}}
    """.trimIndent()

    @Test
    fun parsesSharingFieldsAndCapabilityFeatures() {
        val book = ApiJson.decodeFromString(
            BookDto.serializer(),
            """{"fileId":1,"format":"epub","path":"/Shared/x.epub","size":1,"owner":"bob","shared":true,"sharedOut":false}""",
        )
        assertEquals("bob", book.owner)
        assertTrue(book.shared)
        assertEquals(false, book.sharedOut)
        val own = ApiJson.decodeFromString(BookDto.serializer(), """{"fileId":2,"format":"epub","path":"/a.epub","size":1,"sharedOut":true}""")
        assertNull(own.owner)
        assertEquals(false, own.shared)
        assertTrue(own.sharedOut)

        val caps = ApiJson.decodeFromString(
            EbookReaderCapabilities.serializer(),
            """{"version":"0.10.0","sharing":true,"features":["shared-filter","series-shares","folder-shares","folders","sidecar-meta"]}""",
        )
        assertTrue(caps.sharing)
        assertTrue("folders" in caps.features)
        assertEquals(emptyList<String>(), ApiJson.decodeFromString(EbookReaderCapabilities.serializer(), """{"version":"0.9.0"}""").features)
    }

    @Test
    fun parsesSyncEnvelope() {
        val envelope = ApiJson.decodeFromString<OcsEnvelope<SyncDto>>(syncJson)
        assertEquals(200, envelope.ocs.meta.statuscode)
        val sync = envelope.ocs.data
        assertEquals(listOf(7L), sync.deleted)
        assertEquals("abc==", sync.cursor)
        val book = sync.books.single()
        assertEquals(12L, book.fileId)
        assertEquals(listOf("X", "Y"), book.authors)
        assertEquals(1.5, book.seriesIndex!!, 0.0)
        assertEquals(ReadStatus.READING, book.readStatus)
        assertNull(book.description)
        assertTrue(book.editable)
        assertEquals("epubcfi(/6/2)", book.progress!!.locator.locations!!.cfi)
    }

    @Test
    fun locatorRoundTripOmitsNulls() {
        val json = ApiJson.encodeToString(Locator.serializer(), Locator(href = "a.xhtml", locations = Locations(progression = 0.1)))
        assertEquals("""{"href":"a.xhtml","locations":{"progression":0.1}}""", json)
    }
}
