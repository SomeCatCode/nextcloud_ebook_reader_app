package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.db.BookEntity
import java.util.Locale

/*
 * Reading order of the volumes of a series (docs of the server: "Volume order"). Plain Kotlin, no Android
 * dependencies, so it is unit tested without a device.
 *
 * Order: volume number numerically (books without a number last), then the title with NATURAL comparison
 * ("2" before "10"), then the file id. The volume number is the metadata `seriesIndex`; when it is missing it
 * is parsed from the title or the file name.
 */

/** Natural string order: digit runs compare by value ("vol 2" before "vol 10"), the rest case-insensitively. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pt = p.trimStart('0')
                val qt = q.trimStart('0')
                pt.length.compareTo(qt.length).takeIf { it != 0 } ?: pt.compareTo(qt)
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}

object VolumeNumbers {

    private const val NUMBER = """0*(\d{1,5}(?:\.\d{1,3})?)"""
    private const val LONG_VOLUME = """vol(?:ume|umen|\.)?|band|bd|tome|tomo|livre|book|buch|teil|part|volumen|deel|bok"""
    private const val CHAPTER = "chapter|chap|ch|kapitel|kap|episode|ep|capitulo|cap[ií]tulo|chapitre"
    private const val NOT_AFTER = """(?![\p{L}\p{N}]|\.\d)"""
    private const val NOT_BEFORE = """(?<![\p{L}\p{N}])"""
    private val CI = setOf(RegexOption.IGNORE_CASE)

    /** Same patterns and order as the server (`lib/Metadata/VolumeNumber.php`). */
    private val patterns = listOf(
        // "Vol. 10", "Volume 12", "Band 3", "Tome 4", "Book 2"
        Regex("""$NOT_BEFORE(?:$LONG_VOLUME)\s*\.?\s*$NUMBER$NOT_AFTER""", CI),
        // "v02", "v.7", "T04", "#7", "No. 5"
        Regex("""$NOT_BEFORE(?:v|t|#|no|nr|n°|№)\s*\.?\s*$NUMBER$NOT_AFTER""", CI),
        // chapters
        Regex("""$NOT_BEFORE(?:$CHAPTER)\s*\.?\s*$NUMBER$NOT_AFTER""", CI),
        // "第3巻", "第12話"
        Regex("""第\s*$NUMBER\s*[巻卷冊册話话章回]"""),
    )
    private val extension = Regex("""\.(?:fb2\.zip|[A-Za-z0-9]{2,5})$""")
    private val decoration = Regex("""\s*(?:\([^()]*\)|\[[^\[\]]*\])\s*$""")
    private val trailing = Regex("""(?:^|[\s\-–:,])$NUMBER$""")
    private val leadingNumber = Regex("""^$NUMBER\s*(?:[-–.)_:]\s|\s)""")
    private val year = Regex("""^(?:19|20)\d{2}$""")

    /** Volume number found in [text] (a leading number only with [leading], for file names), or null. */
    fun parse(text: String?, leading: Boolean = false): Double? {
        if (text == null) return null
        var name = text.replace('_', ' ').trim()
        if (name.isEmpty()) return null
        name = extension.replace(name, "")
        for (p in patterns) p.find(name)?.let { return it.groupValues[1].toDouble() }
        // trailing number, ignoring trailing "(Digital)" / "[Group]"
        var tail = name
        while (true) {
            val before = tail
            tail = decoration.replace(tail, "").trim()
            if (tail == before) break
        }
        trailing.find(tail)?.let { m -> if (!year.matches(m.groupValues[1])) return m.groupValues[1].toDouble() }
        if (leading) leadingNumber.find("$tail ")?.let { m -> if (!year.matches(m.groupValues[1])) return m.groupValues[1].toDouble() }
        return null
    }

    /**
     * Volume number of a book of a series: the metadata value; else parsed from the title, then from the file
     * name (where a leading number counts, too). Books without a series have none (nothing orders them).
     */
    fun effective(series: String?, seriesIndex: Double?, title: String?, path: String?): Double? {
        if (seriesIndex != null) return seriesIndex
        if (series.isNullOrBlank()) return null
        return parse(title) ?: parse(path?.substringAfterLast('/'), leading = true)
    }
}

/** Anything with the three fields that decide the volume order. */
interface Volume {
    val volumeNumber: Double?
    val volumeTitle: String
    val volumeKey: Long
}

/** Volume number, then natural title, then file id. */
val VolumeComparator: Comparator<Volume> = Comparator { a, b ->
    val x = a.volumeNumber
    val y = b.volumeNumber
    when {
        x != null && y != null && x != y -> x.compareTo(y)
        x != null && y == null -> -1
        x == null && y != null -> 1
        else -> NaturalOrder.compare(a.volumeTitle, b.volumeTitle).takeIf { it != 0 } ?: a.volumeKey.compareTo(b.volumeKey)
    }
}

/** Descending: highest number first, books without a number still last. */
val VolumeComparatorDesc: Comparator<Volume> = Comparator { a, b ->
    val x = a.volumeNumber
    val y = b.volumeNumber
    when {
        x != null && y != null && x != y -> y.compareTo(x)
        x != null && y == null -> -1
        x == null && y != null -> 1
        else -> NaturalOrder.compare(b.volumeTitle, a.volumeTitle).takeIf { it != 0 } ?: b.volumeKey.compareTo(a.volumeKey)
    }
}

/** The books of a series in reading order. */
fun List<LibraryBook>.inVolumeOrder(): List<LibraryBook> = sortedWith(VolumeComparator)

/** A stored book as a [Volume] (for code that works on entities). */
internal class EntityVolume(val entity: BookEntity) : Volume {
    override val volumeNumber: Double? = VolumeNumbers.effective(entity.series, entity.seriesIndex, entity.fallbackTitle(), entity.path)
    override val volumeTitle: String = entity.fallbackTitle()
    override val volumeKey: Long = entity.fileId
}

internal fun BookEntity.toVolumeRow() = EntityVolume(this)
