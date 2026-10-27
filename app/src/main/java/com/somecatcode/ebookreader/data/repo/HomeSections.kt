package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.ReadStatus
import kotlin.random.Random

/*
 * Sections of the home screen. Plain Kotlin on books that are already loaded from Room (no network),
 * unit tested. Input is always the whole library of the shown accounts, sorted "recently read first".
 */

/** A randomly picked shelf together with its books. */
data class HomeShelf(val shelf: ShelfInfo, val books: List<LibraryBook>)

data class HomeSections(
    val continueReading: List<LibraryBook> = emptyList(),
    val offline: List<LibraryBook> = emptyList(),
    val suggestions: List<LibraryBook> = emptyList(),
    val shelves: List<HomeShelf> = emptyList(),
) {
    val isEmpty: Boolean get() = continueReading.isEmpty() && offline.isEmpty() && suggestions.isEmpty() && shelves.isEmpty()
}

object HomeLogic {
    const val ROW_LIMIT = 12
    const val SUGGESTION_LIMIT = 10
    const val SUGGESTION_SEEDS = 3
    const val MIN_SHELVES = 2
    const val MAX_SHELVES = 3

    /** Stored form of a shelf in [AppSettings.homeShelves]. */
    fun shelfPref(key: ShelfKey): String = "${key.shelfId}:${key.accountId}"

    /**
     * Books to "continue reading": the books with the read status "reading", most recently read first.
     * [recentlyReadFirst] must already be sorted that way. Used by the library repository, too.
     */
    fun continueReading(recentlyReadFirst: List<LibraryBook>, limit: Int = ROW_LIMIT): List<LibraryBook> =
        recentlyReadFirst.filter { it.readStatus == ReadStatus.READING }.take(limit)

    /** Books that are readable without a connection (downloaded or from a local library), newest added first. */
    fun offline(library: List<LibraryBook>, limit: Int = ROW_LIMIT): List<LibraryBook> =
        library.filter { it.offline.isAvailableOffline || it.local }
            .sortedWith(compareByDescending<LibraryBook> { it.addedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .take(limit)

    /**
     * Suggestions from the last [seeds] books that were read or are being read ([recentlyReadFirst] order):
     * for every seed the next volume and the similar unread titles of [ReadNext], merged round-robin
     * (so every seed gets its share), without duplicates, without the seeds, finished books and the books
     * in [exclude] (the "continue reading" row).
     */
    fun suggestions(
        recentlyReadFirst: List<LibraryBook>,
        exclude: Set<BookKey> = emptySet(),
        seeds: Int = SUGGESTION_SEEDS,
        limit: Int = SUGGESTION_LIMIT,
    ): List<LibraryBook> {
        val seedBooks = recentlyReadFirst.filter { it.readStatus != ReadStatus.UNREAD }.take(seeds)
        if (seedBooks.isEmpty()) return emptyList()
        val perSeed = seedBooks.map { seed ->
            val r = ReadNext.compute(seed, recentlyReadFirst, ReadNext.MAX_LIMIT)
            listOfNotNull(r.next) + r.similar
        }
        val blocked = exclude + seedBooks.map { it.key }
        val seen = HashSet<BookKey>()
        val result = ArrayList<LibraryBook>()
        val rounds = perSeed.maxOf { it.size }
        for (i in 0 until rounds) {
            for (list in perSeed) {
                val b = list.getOrNull(i) ?: continue
                if (b.readStatus == ReadStatus.FINISHED || b.key in blocked || !seen.add(b.key)) continue
                result += b
                if (result.size >= limit) return result
            }
        }
        return result
    }

    /**
     * 2 or 3 random shelves out of the [selected] ones (see [shelfPref]) that have books; fewer when fewer
     * qualify. The same [random] seed picks the same shelves.
     */
    fun pickShelves(shelves: List<ShelfInfo>, selected: Set<String>, random: Random): List<ShelfInfo> {
        val candidates = shelves.filter { it.count > 0 && shelfPref(it.key) in selected }
        if (candidates.size <= MIN_SHELVES) return candidates.shuffled(random)
        val n = random.nextInt(MIN_SHELVES, MAX_SHELVES + 1)
        return candidates.shuffled(random).take(n)
    }

    /** The rows that need no database access beyond the loaded library. */
    fun build(recentlyReadFirst: List<LibraryBook>, shelves: List<HomeShelf> = emptyList()): HomeSections {
        val reading = continueReading(recentlyReadFirst)
        return HomeSections(
            continueReading = reading,
            offline = offline(recentlyReadFirst),
            suggestions = suggestions(recentlyReadFirst, exclude = reading.map { it.key }.toSet()),
            shelves = shelves.filter { it.books.isNotEmpty() },
        )
    }
}
