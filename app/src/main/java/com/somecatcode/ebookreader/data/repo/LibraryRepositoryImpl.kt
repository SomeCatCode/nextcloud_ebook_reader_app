package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.db.BookTagEntity
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.PendingEditEntity
import com.somecatcode.ebookreader.data.db.ProgressEntity
import com.somecatcode.ebookreader.data.db.ShelfBookEntity
import com.somecatcode.ebookreader.data.db.ShelfEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Library read side, served entirely from Room (works offline). Filtering that Room cannot express
 * (tags, shelves, smart shelves, offline only, sort orders) is applied in memory on the result of
 * the DAO query, which is fine for libraries of a few thousand books.
 */
class LibraryRepositoryImpl(private val db: AppDatabase) : LibraryRepository {

    override fun books(accountIds: List<String>, filter: LibraryFilter): Flow<List<LibraryBook>> {
        if (accountIds.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        val search = filter.search?.trim()?.takeIf { it.isNotEmpty() }
        val status = filter.status?.let { statusWire(it) }
        return combine(
            db.bookDao().observeLibrary(accountIds, status, search),
            db.bookTagDao().observeAll(accountIds),
            db.progressDao().observeAll(accountIds),
            db.downloadDao().observeAll(accountIds),
            db.pendingEditDao().observeAll(accountIds),
            db.shelfDao().observeAllMembers(accountIds),
        ) { arr -> arr }
            .combine(db.shelfDao().observeAll(accountIds)) { arr, shelves ->
                @Suppress("UNCHECKED_CAST")
                assemble(
                    filter,
                    arr[0] as List<BookEntity>,
                    arr[1] as List<BookTagEntity>,
                    arr[2] as List<ProgressEntity>,
                    arr[3] as List<DownloadEntity>,
                    arr[4] as List<PendingEditEntity>,
                    arr[5] as List<ShelfBookEntity>,
                    shelves,
                )
            }
            .flowOn(Dispatchers.Default)
    }

    private fun assemble(
        filter: LibraryFilter,
        books: List<BookEntity>,
        tags: List<BookTagEntity>,
        progress: List<ProgressEntity>,
        downloads: List<DownloadEntity>,
        pending: List<PendingEditEntity>,
        members: List<ShelfBookEntity>,
        shelves: List<ShelfEntity>,
    ): List<LibraryBook> {
        val tagsBy = tags.groupBy { it.accountId to it.fileId }
        val progressBy = progress.associateBy { it.accountId to it.fileId }
        val downloadBy = downloads.associateBy { it.accountId to it.fileId }
        val pendingBy = pending.map { it.accountId to it.fileId }.toSet()

        var list = books
        filter.shelf?.let { key ->
            list = list.filter { it.accountId == key.accountId }
            val shelf = shelves.firstOrNull { it.accountId == key.accountId && it.id == key.shelfId }
            list = if (shelf == null) emptyList() else filterByShelf(list, shelf, tagsBy, members, shelves)
        }
        filter.series?.let { s -> list = list.filter { it.series.equals(s, ignoreCase = true) } }
        if (filter.genres.isNotEmpty()) {
            list = list.filter { b -> tagNames(tagsBy[b.accountId to b.fileId], TAG_GENRE).containsAll(filter.genres) }
        }
        if (filter.tags.isNotEmpty()) {
            list = list.filter { b -> tagNames(tagsBy[b.accountId to b.fileId], TAG_TAG).containsAll(filter.tags) }
        }
        if (filter.onlyOffline) {
            list = list.filter { downloadBy[it.accountId to it.fileId]?.state == "DONE" }
        }
        val mapped = list.map {
            val k = it.accountId to it.fileId
            it.toLibraryBook(tagsBy[k].orEmpty(), progressBy[k], downloadBy[k], k in pendingBy)
        }
        return sorted(mapped, filter, progressBy)
    }

    private fun filterByShelf(
        books: List<BookEntity>,
        shelf: ShelfEntity,
        tagsBy: Map<Pair<String, Long>, List<BookTagEntity>>,
        members: List<ShelfBookEntity>,
        shelves: List<ShelfEntity>,
    ): List<BookEntity> {
        if (!shelf.isSmart()) {
            val ids = members.filter { it.accountId == shelf.accountId && it.shelfId == shelf.id }.map { it.fileId }.toSet()
            return books.filter { it.fileId in ids }
        }
        val query = shelf.smartQuery() ?: return emptyList()
        val memberMap = members.filter { it.accountId == shelf.accountId }
            .groupBy({ it.shelfId }, { it.fileId }).mapValues { it.value.toSet() }
        return books.filter { SmartQueryEvaluator.matches(query, it, tagsBy[it.accountId to it.fileId].orEmpty(), memberMap) }
    }

    private fun tagNames(tags: List<BookTagEntity>?, type: String): Set<String> =
        tags.orEmpty().filter { it.type == type }.map { it.name }.toSet()

    private fun sorted(list: List<LibraryBook>, filter: LibraryFilter, progressBy: Map<Pair<String, Long>, ProgressEntity>): List<LibraryBook> {
        val ci = String.CASE_INSENSITIVE_ORDER
        val base: Comparator<LibraryBook> = when (filter.sort) {
            LibrarySort.TITLE -> compareBy(ci) { it.title }
            LibrarySort.AUTHOR -> compareBy<LibraryBook, String>(ci) { it.authors.firstOrNull().orEmpty() }.thenBy(ci) { it.title }
            LibrarySort.SERIES -> compareBy<LibraryBook, String>(ci) { it.series.orEmpty() }
                .thenBy { it.seriesIndex ?: Double.MAX_VALUE }.thenBy(ci) { it.title }
            LibrarySort.RATING -> compareBy<LibraryBook> { it.rating ?: -1 }.thenBy(ci) { it.title }
            LibrarySort.ADDED -> compareBy<LibraryBook> { it.addedAt }.thenBy(ci) { it.title }
            LibrarySort.RECENTLY_READ -> compareBy<LibraryBook> { progressBy[it.key.accountId to it.key.fileId]?.clientUpdatedAt ?: 0L }
                .thenBy(ci) { it.title }
        }
        return list.sortedWith(if (filter.descending) base.reversed() else base)
    }

    override fun book(key: BookKey): Flow<LibraryBook?> = combine(
        db.bookDao().observe(key.accountId, key.fileId),
        db.bookTagDao().observeForBook(key.accountId, key.fileId),
        db.progressDao().observe(key.accountId, key.fileId),
        db.downloadDao().observe(key.accountId, key.fileId),
        db.pendingEditDao().observeCountForBook(key.accountId, key.fileId),
    ) { book, tags, progress, download, pending ->
        book?.takeIf { !it.deleted }?.toLibraryBook(tags, progress, download, pending > 0)
    }.flowOn(Dispatchers.Default)

    override fun shelves(accountIds: List<String>): Flow<List<ShelfInfo>> =
        db.shelfDao().observeAll(accountIds).map { rows ->
            rows.map {
                ShelfInfo(ShelfKey(it.accountId, it.id), it.name, it.isSmart(), it.count, decodeLongs(it.coverFileIds))
            }
        }

    override fun series(accountIds: List<String>): Flow<List<SeriesInfo>> =
        db.bookDao().observeLibrary(accountIds, null, null).map { books ->
            books.filter { !it.series.isNullOrBlank() }
                .groupBy { it.accountId to it.series!! }
                .map { (key, group) ->
                    val ordered = group.sortedWith(compareBy({ it.seriesIndex ?: Double.MAX_VALUE }, { it.title?.lowercase() }))
                    SeriesInfo(
                        accountId = key.first,
                        name = key.second,
                        count = group.size,
                        readCount = group.count { it.readStatus == "finished" },
                        coverFileIds = ordered.filter { it.hasCover }.take(3).map { it.fileId },
                    )
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    override fun genres(accountIds: List<String>): Flow<List<FacetCount>> = facet(accountIds, TAG_GENRE)

    override fun tags(accountIds: List<String>): Flow<List<FacetCount>> = facet(accountIds, TAG_TAG)

    private fun facet(accountIds: List<String>, type: String): Flow<List<FacetCount>> =
        db.bookTagDao().observeFacet(accountIds, type).map { rows -> rows.map { FacetCount(it.name, it.count) } }

    override fun continueReading(accountIds: List<String>, limit: Int): Flow<List<LibraryBook>> =
        books(accountIds, LibraryFilter(sort = LibrarySort.RECENTLY_READ, descending = true)).map { list ->
            list.filter { it.percentage != null && it.readStatus != ReadStatus.FINISHED }.take(limit)
        }

    private fun statusWire(s: ReadStatus) = when (s) {
        ReadStatus.UNREAD -> "unread"
        ReadStatus.READING -> "reading"
        ReadStatus.FINISHED -> "finished"
    }
}
