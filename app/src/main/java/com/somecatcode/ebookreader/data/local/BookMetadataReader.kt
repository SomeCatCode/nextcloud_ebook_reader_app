package com.somecatcode.ebookreader.data.local

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Metadata of a book file in a local library (what the server extracts for Nextcloud books). */
data class ExtractedBook(
    val title: String,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Double? = null,
    val description: String? = null,
    val language: String? = null,
    val publisher: String? = null,
    val isbn: String? = null,
    val publishedAt: String? = null,
    val genres: List<String> = emptyList(),
    /** Long strip comic (webtoon): recognised from ComicInfo.xml of a CBZ. */
    val longstrip: Boolean = false,
    /** Cover image bytes (JPEG/PNG/...), null when the file has none or it could not be found. */
    val cover: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ExtractedBook && copy(cover = null).toString() == other.copy(cover = null).toString() &&
            (cover?.contentEquals(other.cover) ?: (other.cover == null))

    override fun hashCode(): Int = copy(cover = null).toString().hashCode()
}

/**
 * Reads title, authors, series, description, ... and the cover of EPUB, FB2/FBZ and CBZ files. Other
 * formats (MOBI, AZW3, CBR, CB7, CBT) and unreadable files fall back to the file name as title.
 * [open] is called once per pass over the file (ZIP files are read as a stream, without random access).
 */
object BookMetadataReader {

    /** Formats a local library lists (same as the server). */
    val FORMATS = setOf("epub", "mobi", "azw3", "fb2", "fbz", "cbz", "cbr", "cb7", "cbt")

    private const val MAX_TEXT_ENTRY = 4L * 1024 * 1024
    private const val MAX_COVER = 10L * 1024 * 1024
    private const val MAX_FB2 = 64L * 1024 * 1024
    private val IMAGE = Regex("""\.(jpe?g|png|gif|webp|bmp)$""", RegexOption.IGNORE_CASE)

    fun formatOf(fileName: String): String? =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it in FORMATS }

    fun read(format: String, fileName: String, open: () -> InputStream): ExtractedBook {
        val fallback = ExtractedBook(title = titleFromFileName(fileName))
        return try {
            when (format) {
                "epub" -> readEpub(open) ?: fallback
                "fb2" -> open().use { readFb2(limited(it, MAX_FB2)) } ?: fallback
                "fbz" -> readFbz(open) ?: fallback
                "cbz" -> readCbz(open, fallback)
                else -> fallback
            }.let { if (it.title.isBlank()) it.copy(title = fallback.title) else it }
        } catch (e: Exception) {
            fallback
        }
    }

    fun titleFromFileName(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { fileName }

    // ---- EPUB ------------------------------------------------------------------------------------

    private fun readEpub(open: () -> InputStream): ExtractedBook? {
        // Pass 1: container.xml and every OPF (small text files); the OPF names the cover.
        val texts = HashMap<String, ByteArray>()
        zipEntries(open) { name, input ->
            if (name == "META-INF/container.xml" || name.endsWith(".opf", ignoreCase = true)) {
                texts[name] = readLimited(input, MAX_TEXT_ENTRY) ?: return@zipEntries true
            }
            true
        }
        val container = texts["META-INF/container.xml"]?.let(::parseXml)
        val opfPath = container?.let { doc ->
            elements(doc.documentElement, "rootfile").firstOrNull()?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
        } ?: texts.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) } ?: return null
        val opf = texts[opfPath]?.let(::parseXml) ?: return null
        val root = opf.documentElement
        val metadata = elements(root, "metadata").firstOrNull() ?: root

        fun dc(name: String) = elements(metadata, name).mapNotNull { text(it) }
        val metas = elements(metadata, "meta")
        fun named(name: String) = metas.firstOrNull { it.getAttribute("name") == name }?.getAttribute("content")?.trim()?.takeIf { it.isNotEmpty() }

        var series = named("calibre:series")
        var seriesIndex = named("calibre:series_index")?.toDoubleOrNull()
        if (series == null) {
            // EPUB 3: <meta property="belongs-to-collection" id="c">Name</meta> + <meta refines="#c" property="group-position">
            metas.firstOrNull { it.getAttribute("property") == "belongs-to-collection" }?.let { coll ->
                series = text(coll)
                val id = coll.getAttribute("id")
                if (id.isNotEmpty()) {
                    seriesIndex = metas.firstOrNull { it.getAttribute("refines") == "#$id" && it.getAttribute("property") == "group-position" }
                        ?.let { text(it) }?.toDoubleOrNull()
                }
            }
        }

        val isbn = elements(metadata, "identifier").mapNotNull { text(it) }
            .map { it.removePrefix("urn:isbn:").removePrefix("isbn:") }
            .firstOrNull { it.replace("-", "").matches(Regex("""\d{9}[\dXx]|\d{13}""")) }

        val coverHref = coverHref(root, named("cover"))
        val cover = coverHref?.let { href ->
            val target = resolve(opfPath, href)
            var bytes: ByteArray? = null
            zipEntries(open) { name, input ->
                if (name == target) {
                    bytes = readLimited(input, MAX_COVER)
                    false
                } else {
                    true
                }
            }
            bytes
        }

        return ExtractedBook(
            title = dc("title").firstOrNull().orEmpty(),
            authors = dc("creator").distinct(),
            series = series,
            seriesIndex = seriesIndex,
            description = dc("description").firstOrNull(),
            language = dc("language").firstOrNull(),
            publisher = dc("publisher").firstOrNull(),
            isbn = isbn,
            publishedAt = dc("date").firstOrNull(),
            genres = dc("subject").distinct(),
            cover = cover,
        )
    }

    /** Manifest href of the cover: EPUB 3 `properties="cover-image"`, else the EPUB 2 `<meta name="cover">` id. */
    private fun coverHref(root: Element, coverId: String?): String? {
        val items = elements(elements(root, "manifest").firstOrNull() ?: return null, "item")
        items.firstOrNull { it.getAttribute("properties").split(' ').contains("cover-image") }?.let { return it.getAttribute("href") }
        if (coverId != null) items.firstOrNull { it.getAttribute("id") == coverId }?.let { return it.getAttribute("href") }
        return items.firstOrNull { it.getAttribute("id").contains("cover", ignoreCase = true) && it.getAttribute("media-type").startsWith("image/") }
            ?.getAttribute("href")
    }

    /** Path of [href] (relative to the OPF, percent-encoded) inside the ZIP. */
    internal fun resolve(opfPath: String, href: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
        val base = opfPath.substringBeforeLast('/', "")
        val parts = ArrayDeque<String>()
        if (base.isNotEmpty()) base.split('/').forEach { parts.addLast(it) }
        for (p in decoded.substringBefore('#').split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(p)
            }
        }
        return parts.joinToString("/")
    }

    // ---- FB2 / FBZ -------------------------------------------------------------------------------

    private fun readFbz(open: () -> InputStream): ExtractedBook? {
        var result: ExtractedBook? = null
        zipEntries(open) { name, input ->
            if (name.endsWith(".fb2", ignoreCase = true)) {
                result = readFb2(limited(input, MAX_FB2))
                false
            } else {
                true
            }
        }
        return result
    }

    private fun readFb2(input: InputStream): ExtractedBook? {
        val doc = parseXml(input.readBytes()) ?: return null
        val root = doc.documentElement
        val description = elements(root, "description").firstOrNull() ?: return null
        val info = elements(description, "title-info").firstOrNull() ?: return null
        val publish = elements(description, "publish-info").firstOrNull()

        val authors = elements(info, "author").mapNotNull { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { n -> elements(a, n).firstOrNull()?.let(::text) }
                .joinToString(" ").ifBlank { elements(a, "nickname").firstOrNull()?.let(::text) }
        }
        val sequence = elements(info, "sequence").firstOrNull()
        val coverId = elements(info, "coverpage").firstOrNull()?.let { cp ->
            elements(cp, "image").firstOrNull()?.let { img ->
                (0 until img.attributes.length).map { img.attributes.item(it) }
                    .firstOrNull { it.nodeName.endsWith("href") }?.nodeValue?.removePrefix("#")
            }
        }
        val cover = coverId?.let { id ->
            elements(root, "binary").firstOrNull { it.getAttribute("id") == id }?.let { bin ->
                runCatching { Base64.getMimeDecoder().decode(bin.textContent.trim()) }.getOrNull()
            }
        }
        return ExtractedBook(
            title = elements(info, "book-title").firstOrNull()?.let(::text).orEmpty(),
            authors = authors.distinct(),
            series = sequence?.getAttribute("name")?.trim()?.takeIf { it.isNotEmpty() },
            seriesIndex = sequence?.getAttribute("number")?.toDoubleOrNull(),
            description = elements(info, "annotation").firstOrNull()?.let(::text),
            language = elements(info, "lang").firstOrNull()?.let(::text),
            publisher = publish?.let { elements(it, "publisher").firstOrNull()?.let(::text) },
            isbn = publish?.let { elements(it, "isbn").firstOrNull()?.let(::text) },
            publishedAt = publish?.let { elements(it, "year").firstOrNull()?.let(::text) }
                ?: elements(info, "date").firstOrNull()?.let(::text),
            genres = elements(info, "genre").mapNotNull(::text).distinct(),
            cover = cover,
        )
    }

    // ---- comics ----------------------------------------------------------------------------------

    private val LONGSTRIP_WORDS = setOf("webtoon", "web toon", "long strip", "vertical")

    /**
     * Long strip comic like the server: ComicInfo.xml `Format` contains "Webtoon", "Web Toon", "Long Strip" or
     * "Vertical" (case-insensitive), or a `Tags`/`Genre` entry is exactly one of these.
     */
    internal fun isLongstripComicInfo(xml: ByteArray): Boolean {
        val root = parseXml(xml)?.documentElement ?: return false
        val format = elements(root, "Format").firstOrNull()?.let(::text)?.lowercase(Locale.ROOT).orEmpty()
        if (LONGSTRIP_WORDS.any { format.contains(it) }) return true
        return listOf("Tags", "Genre").flatMap { name -> elements(root, name).mapNotNull(::text) }
            .flatMap { it.split(',', ';') }
            .any { it.trim().lowercase(Locale.ROOT) in LONGSTRIP_WORDS }
    }

    /** CBZ: cover = first page in natural name order, long strip flag from ComicInfo.xml (pass 1: names + ComicInfo, pass 2: the image). */
    private fun readCbz(open: () -> InputStream, base: ExtractedBook): ExtractedBook {
        val names = ArrayList<String>()
        var comicInfo: ByteArray? = null
        zipEntries(open) { name, input ->
            if (IMAGE.containsMatchIn(name) && !name.startsWith("__MACOSX/")) {
                names += name
            } else if (comicInfo == null && name.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true)) {
                comicInfo = readLimited(input, MAX_TEXT_ENTRY)
            }
            true
        }
        val longstrip = comicInfo?.let { runCatching { isLongstripComicInfo(it) }.getOrDefault(false) } ?: false
        val first = names.minWithOrNull(NaturalOrder) ?: return base.copy(longstrip = longstrip)
        var bytes: ByteArray? = null
        zipEntries(open) { name, input ->
            if (name == first) {
                bytes = readLimited(input, MAX_COVER)
                false
            } else {
                true
            }
        }
        return base.copy(cover = bytes, longstrip = longstrip)
    }

    /** "page2" before "page10". */
    internal object NaturalOrder : Comparator<String> {
        private val chunk = Regex("""\d+|\D+""")
        override fun compare(a: String, b: String): Int {
            val x = chunk.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
            val y = chunk.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
                } else {
                    p.compareTo(q)
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Streams the entries of a ZIP; [visit] returns false to stop. */
    private fun zipEntries(open: () -> InputStream, visit: (String, InputStream) -> Boolean) {
        ZipInputStream(open().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && !visit(entry.name.trimStart('/'), zip)) break
            }
        }
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray? {
        val bytes = limited(input, max + 1).readBytes()
        return bytes.takeIf { it.size <= max }
    }

    private fun limited(input: InputStream, max: Long): InputStream = object : InputStream() {
        var left = max
        override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private fun parseXml(bytes: ByteArray): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // books are untrusted: no DTDs, no external entities
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) -> runCatching { factory.setFeature(feature, value) } }
        runCatching { factory.isExpandEntityReferences = false }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }.getOrNull()

    /** Descendant elements by local name (namespace prefixes differ between books). */
    private fun elements(parent: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null) {
                if (child is Element) {
                    val name = child.localName ?: child.nodeName.substringAfter(':')
                    if (name == localName) out += child
                    walk(child)
                }
                child = child.nextSibling
            }
        }
        walk(parent)
        return out
    }

    private fun text(e: Element): String? = e.textContent?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }
}
