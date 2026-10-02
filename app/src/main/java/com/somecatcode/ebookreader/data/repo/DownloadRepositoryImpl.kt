package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.download.DownloadManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Offline management on top of the `download` table and [DownloadManager]. Pins live in the rows
 * themselves (`pinnedBy` + `pinRef`: shelf id or series name); the sync engine adds new members of
 * pinned shelves/series. Limitation: a shelf/series that had no downloadable member when it was
 * pinned leaves no row and is therefore not remembered.
 */
class DownloadRepositoryImpl(
    private val db: AppDatabase,
    private val manager: DownloadManager,
) : DownloadRepository {

    override val items: Flow<List<OfflineItem>> = db.downloadDao().observeItems().map { rows ->
        rows.map { r ->
            OfflineItem(
                key = BookKey(r.accountId, r.fileId),
                title = r.title?.takeIf { it.isNotBlank() } ?: r.path?.substringAfterLast('/').orEmpty(),
                state = runCatching { DownloadState.valueOf(r.state) }.getOrDefault(DownloadState.QUEUED),
                bytes = r.bytes,
                total = r.total,
                pinnedBy = runCatching { PinnedBy.valueOf(r.pinnedBy) }.getOrDefault(PinnedBy.BOOK),
                error = r.errorMessage,
            )
        }
    }

    override val usedBytes: Flow<Long> = db.downloadDao().observeUsedBytes()

    override suspend fun makeAvailableOffline(target: OfflineTarget) {
        when (target) {
            is OfflineTarget.Book -> manager.enqueue(target.key, PinnedBy.BOOK, null)
            is OfflineTarget.Shelf -> {
                val ref = target.key.shelfId.toString()
                for (book in shelfBooks(target.key)) manager.enqueue(BookKey(book.accountId, book.fileId), PinnedBy.SHELF, ref)
            }
            is OfflineTarget.Series -> {
                for (book in seriesBooks(target.accountId, target.name)) {
                    manager.enqueue(BookKey(book.accountId, book.fileId), PinnedBy.SERIES, target.name)
                }
            }
        }
    }

    override suspend fun removeOffline(target: OfflineTarget) {
        when (target) {
            is OfflineTarget.Book -> {
                val row = db.downloadDao().get(target.key.accountId, target.key.fileId) ?: return
                releaseRow(row, ignore = PinKey(PinnedBy.BOOK, null), accountRows = db.downloadDao().forAccount(row.accountId))
            }
            is OfflineTarget.Shelf -> {
                val ref = target.key.shelfId.toString()
                val rows = db.downloadDao().forAccount(target.key.accountId)
                rows.filter { it.pinnedBy == PinnedBy.SHELF.name && it.pinRef == ref }
                    .forEach { releaseRow(it, PinKey(PinnedBy.SHELF, ref), rows) }
            }
            is OfflineTarget.Series -> {
                val rows = db.downloadDao().forAccount(target.accountId)
                rows.filter { it.pinnedBy == PinnedBy.SERIES.name && it.pinRef == target.name }
                    .forEach { releaseRow(it, PinKey(PinnedBy.SERIES, target.name), rows) }
            }
        }
    }

    override suspend fun retry(key: BookKey) {
        val row = db.downloadDao().get(key.accountId, key.fileId) ?: return
        manager.enqueue(key, runCatching { PinnedBy.valueOf(row.pinnedBy) }.getOrDefault(PinnedBy.BOOK), row.pinRef)
    }

    override suspend fun cancel(key: BookKey) = manager.cancel(key)

    override suspend fun clearAll() {
        for (row in db.downloadDao().all()) manager.delete(BookKey(row.accountId, row.fileId))
    }

    override suspend fun localFile(key: BookKey): File? = manager.localFile(key)

    override fun downloadEntity(key: BookKey): Flow<DownloadEntity?> = db.downloadDao().observe(key.accountId, key.fileId)

    // ---- helpers ----------------------------------------------------------------------------------------

    private data class PinKey(val by: PinnedBy, val ref: String?)

    /**
     * Drops one pin of a row. If the book still belongs to another pinned shelf/series (seen from
     * the other rows of the account) the row is re-pinned to it, otherwise file and row are deleted.
     */
    private suspend fun releaseRow(row: DownloadEntity, ignore: PinKey, accountRows: List<DownloadEntity>) {
        val key = BookKey(row.accountId, row.fileId)
        val otherPins = accountRows
            .filter { it.pinnedBy != PinnedBy.BOOK.name && it.pinRef != null }
            .map { PinKey(PinnedBy.valueOf(it.pinnedBy), it.pinRef) }
            .toSet() - ignore
        val book = db.bookDao().get(row.accountId, row.fileId)
        val replacement = if (book == null) null else otherPins.firstOrNull { belongs(book, it) }
        if (replacement != null) {
            db.downloadDao().upsert(row.copy(pinnedBy = replacement.by.name, pinRef = replacement.ref))
        } else {
            manager.delete(key)
        }
    }

    private suspend fun belongs(book: BookEntity, pin: PinKey): Boolean = when (pin.by) {
        PinnedBy.SERIES -> book.series == pin.ref
        PinnedBy.SHELF -> pin.ref?.toLongOrNull()?.let { shelfBooks(ShelfKey(book.accountId, it)).any { b -> b.fileId == book.fileId } } == true
        PinnedBy.BOOK -> false
    }

    private suspend fun seriesBooks(accountId: String, series: String): List<BookEntity> =
        db.bookDao().getAllActive(accountId).filter { it.series == series && it.downloadable }

    private suspend fun shelfBooks(key: ShelfKey): List<BookEntity> =
        PinnedMembers.shelfBooks(db, key.accountId, key.shelfId).filter { it.downloadable }
}

/** Resolves members of shelves and series locally; shared by the repository and the sync engine. */
internal object PinnedMembers {

    suspend fun shelfBooks(db: AppDatabase, accountId: String, shelfId: Long): List<BookEntity> {
        val shelf = db.shelfDao().get(accountId, shelfId) ?: return emptyList()
        val books = db.bookDao().getAllActive(accountId)
        if (!shelf.isSmart()) {
            val ids = db.shelfDao().fileIds(accountId, shelfId).toSet()
            return books.filter { it.fileId in ids }
        }
        val query = shelf.smartQuery() ?: return emptyList()
        val tags = db.bookTagDao().allOf(accountId).groupBy { it.fileId }
        val memberMap = db.shelfDao().allMembers(accountId).groupBy({ it.shelfId }, { it.fileId }).mapValues { it.value.toSet() }
        return books.filter { SmartQueryEvaluator.matches(query, it, tags[it.fileId].orEmpty(), memberMap) }
    }
}
