package com.somecatcode.ebookreader.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookMetadataReaderTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(format: String, name: String, bytes: ByteArray) = BookMetadataReader.read(format, name) { ByteArrayInputStream(bytes) }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>""".toByteArray()

    @Test
    fun epub2WithCalibreSeriesAndCoverMeta() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>Der Hobbit</dc:title>
                <dc:creator opf:role="aut">J. R. R. Tolkien</dc:creator>
                <dc:language>de</dc:language>
                <dc:publisher>Klett</dc:publisher>
                <dc:identifier opf:scheme="ISBN">urn:isbn:9783608938159</dc:identifier>
                <dc:subject>Fantasy</dc:subject>
                <dc:description>&lt;p&gt;Ein Abenteuer&lt;/p&gt;</dc:description>
                <dc:date>1937-09-21</dc:date>
                <meta name="calibre:series" content="Mittelerde"/>
                <meta name="calibre:series_index" content="1.0"/>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest><item id="cover-img" href="images/my%20cover.png" media-type="image/png"/></manifest>
            </package>""".toByteArray()
        val book = read(
            "epub", "x.epub",
            zip("mimetype" to "application/epub+zip".toByteArray(), "META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/images/my cover.png" to png),
        )
        assertEquals("Der Hobbit", book.title)
        assertEquals(listOf("J. R. R. Tolkien"), book.authors)
        assertEquals("Mittelerde", book.series)
        assertEquals(1.0, book.seriesIndex!!, 0.0)
        assertEquals("de", book.language)
        assertEquals("Klett", book.publisher)
        assertEquals("9783608938159", book.isbn)
        assertEquals(listOf("Fantasy"), book.genres)
        assertEquals("<p>Ein Abenteuer</p>", book.description)
        assertArrayEquals(png, book.cover)
    }

    @Test
    fun epub3CollectionAndCoverImageProperty() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Band Zwei</dc:title>
                <dc:creator>A</dc:creator><dc:creator>B</dc:creator>
                <meta property="belongs-to-collection" id="c1">Reihe</meta>
                <meta refines="#c1" property="group-position">2</meta>
              </metadata>
              <manifest><item id="x" href="../cover.jpg" properties="cover-image" media-type="image/jpeg"/></manifest>
            </package>""".toByteArray()
        val book = read("epub", "x.epub", zip("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "cover.jpg" to png))
        assertEquals("Band Zwei", book.title)
        assertEquals(listOf("A", "B"), book.authors)
        assertEquals("Reihe", book.series)
        assertEquals(2.0, book.seriesIndex!!, 0.0)
        assertArrayEquals(png, book.cover)
    }

    @Test
    fun fb2WithSequenceAndEmbeddedCover() {
        val fb2 = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <description>
                <title-info>
                  <genre>sf</genre>
                  <author><first-name>Arkadi</first-name><last-name>Strugatzki</last-name></author>
                  <book-title>Picknick am Wegesrand</book-title>
                  <annotation><p>Die Zone.</p></annotation>
                  <lang>de</lang>
                  <coverpage><image l:href="#cover.png"/></coverpage>
                  <sequence name="Welt des Mittags" number="3"/>
                </title-info>
                <publish-info><publisher>Suhrkamp</publisher><year>1981</year></publish-info>
              </description>
              <body><section><p>Text</p></section></body>
              <binary id="cover.png" content-type="image/png">${Base64.getEncoder().encodeToString(png)}</binary>
            </FictionBook>""".toByteArray()
        val book = read("fb2", "p.fb2", fb2)
        assertEquals("Picknick am Wegesrand", book.title)
        assertEquals(listOf("Arkadi Strugatzki"), book.authors)
        assertEquals("Welt des Mittags", book.series)
        assertEquals(3.0, book.seriesIndex!!, 0.0)
        assertEquals("Die Zone.", book.description)
        assertEquals("Suhrkamp", book.publisher)
        assertEquals("1981", book.publishedAt)
        assertEquals(listOf("sf"), book.genres)
        assertArrayEquals(png, book.cover)

        val fbz = read("fbz", "p.fbz", zip("p.fb2" to fb2))
        assertEquals("Picknick am Wegesrand", fbz.title)
    }

    @Test
    fun cbzUsesTheFirstPageInNaturalOrder() {
        val page2 = byteArrayOf(2)
        val page10 = byteArrayOf(10)
        val book = read("cbz", "My_Comic 01.cbz", zip("p10.jpg" to page10, "p2.jpg" to page2, "info.txt" to byteArrayOf(0)))
        assertEquals("My Comic 01", book.title)
        assertArrayEquals(page2, book.cover)
    }

    @Test
    fun otherFormatsAndBrokenFilesFallBackToTheFileName() {
        assertEquals("Some Book", read("mobi", "Some Book.mobi", byteArrayOf(1, 2)).title)
        val broken = read("epub", "Broken.epub", byteArrayOf(1, 2, 3))
        assertEquals("Broken", broken.title)
        assertNull(broken.cover)
        assertEquals("x", read("epub", "x.epub", zip("META-INF/container.xml" to container)).title)
    }

    @Test
    fun doctypesAreRejected() {
        val opf = """<?xml version="1.0"?><!DOCTYPE package [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>&x;</dc:title></metadata></package>""".toByteArray()
        val book = read("epub", "evil.epub", zip("META-INF/container.xml" to container, "OEBPS/content.opf" to opf))
        assertEquals("evil", book.title)
    }

    @Test
    fun formatsResolveAndIdsAreStable() {
        assertEquals("epub", BookMetadataReader.formatOf("a/B.EPUB"))
        assertNull(BookMetadataReader.formatOf("notes.txt"))
        assertEquals("OEBPS/images/a b.png", BookMetadataReader.resolve("OEBPS/content.opf", "images/a%20b.png"))
        assertEquals("cover.jpg", BookMetadataReader.resolve("OEBPS/content.opf", "../cover.jpg"))
        val id = localFileId("primary:Books/a.epub")
        assertEquals(id, localFileId("primary:Books/a.epub"))
        assertTrue(id in 1..0x1F_FFFF_FFFF_FFFFL)
        assertTrue(id != localFileId("primary:Books/b.epub"))
    }
}
