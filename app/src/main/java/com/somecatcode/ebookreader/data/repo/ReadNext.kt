package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.ReadStatus
import java.util.Locale

/*
 * "Read next" suggestions after a book (same algorithm as `GET /books/{fileId}/suggestions` of the server,
 * computed locally so it works offline and for local libraries). Plain Kotlin, unit tested.
 */

/** [next] = the next volume of the series (null at the end, without series, or when it is finished); [similar] = unread titles. */
data class ReadNextSuggestions(val next: LibraryBook? = null, val similar: List<LibraryBook> = emptyList()) {
    val isEmpty: Boolean get() = next == null && similar.isEmpty()
}

object ReadNext {
    const val DEFAULT_LIMIT = 4
    const val MAX_LIMIT = 10

    private val COMIC_FORMATS = setOf("cbz", "cbr", "cb7", "cbt")

    private fun norm(s: String?): String = s.orEmpty().trim().lowercase(Locale.ROOT)

    private fun seriesOf(b: LibraryBook): String? = norm(b.series).takeIf { it.isNotEmpty() }

    /**
     * The volume after [book] in reading order, only if it is not finished. [library] = the books of the same
     * account (other accounts never count).
     */
    fun nextVolume(book: LibraryBook, library: List<LibraryBook>): LibraryBook? {
        val series = seriesOf(book) ?: return null
        val ordered = library.filter { it.key.accountId == book.key.accountId && seriesOf(it) == series }.inVolumeOrder()
        val index = ordered.indexOfFirst { it.key == book.key }
        if (index < 0) return null
        return ordered.getOrNull(index + 1)?.takeIf { it.readStatus != ReadStatus.FINISHED }
    }

    /**
     * Unread books that resemble [book]: +3 per shared author, +2 per shared genre, +1 per shared tag, +1 same
     * language, +1 same format family (comic or ebook). Never the book itself or a book of its series, at most
     * one book per other series (its first volume in reading order), only a score above 0. Ties: newer first,
     * then by title.
     */
    fun similar(book: LibraryBook, library: List<LibraryBook>, limit: Int = DEFAULT_LIMIT): List<LibraryBook> {
        val own = seriesOf(book)
        val unread = library.filter {
            it.key.accountId == book.key.accountId && it.key != book.key && it.readStatus == ReadStatus.UNREAD &&
                (own == null || seriesOf(it) != own)
        }
        // one representative per series: the first volume in reading order
        val seriesFirst = unread.filter { seriesOf(it) != null }.groupBy { seriesOf(it) }.values.map { it.inVolumeOrder().first() }
        val candidates = unread.filter { seriesOf(it) == null } + seriesFirst
        return candidates
            .map { it to score(book, it) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<LibraryBook, Int>> { it.second }
                    .thenByDescending { it.first.addedAt }
                    .thenBy(NaturalOrder) { it.first.title },
            )
            .take(limit.coerceIn(1, MAX_LIMIT))
            .map { it.first }
    }

    fun score(book: LibraryBook, other: LibraryBook): Int {
        fun shared(a: List<String>, b: List<String>): Int {
            val x = a.map(::norm).filter { it.isNotEmpty() }.toSet()
            return b.map(::norm).filter { it.isNotEmpty() }.toSet().count { it in x }
        }
        var score = 3 * shared(book.authors, other.authors) + 2 * shared(book.genres, other.genres) + shared(book.tags, other.tags)
        if (norm(book.language).isNotEmpty() && norm(book.language) == norm(other.language)) score++
        if ((book.format.lowercase(Locale.ROOT) in COMIC_FORMATS) == (other.format.lowercase(Locale.ROOT) in COMIC_FORMATS)) score++
        return score
    }

    fun compute(book: LibraryBook, library: List<LibraryBook>, limit: Int = DEFAULT_LIMIT): ReadNextSuggestions =
        ReadNextSuggestions(nextVolume(book, library), similar(book, library, limit))
}
