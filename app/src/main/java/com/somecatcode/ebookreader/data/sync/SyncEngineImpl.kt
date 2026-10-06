package com.somecatcode.ebookreader.data.sync

import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.EbookApi
import com.somecatcode.ebookreader.data.api.SyncDto
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.db.ProgressEntity
import com.somecatcode.ebookreader.data.download.DownloadManager
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.EditRepository
import com.somecatcode.ebookreader.data.repo.PinnedMembers
import com.somecatcode.ebookreader.data.repo.ProgressRepository
import com.somecatcode.ebookreader.data.repo.ShelfSync
import com.somecatcode.ebookreader.data.repo.tagEntities
import com.somecatcode.ebookreader.data.repo.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Delta sync per account (see [SyncEngine]). Every `/sync` page is written in ONE Room transaction
 * together with its cursor; interrupted runs therefore resume without gaps or duplicates.
 */
class SyncEngineImpl(
    private val db: AppDatabase,
    private val apiFactory: ApiClientFactory,
    private val edits: EditRepository,
    private val progress: ProgressRepository,
    private val downloads: DownloadManager,
    private val workManager: () -> WorkManager,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncEngine {

    private val states = MutableStateFlow<Map<String, SyncState>>(emptyMap())
    private val locks = ConcurrentHashMap<String, Mutex>()

    override val state: Flow<Map<String, SyncState>> = states.asStateFlow()

    // ---- scheduling -----------------------------------------------------------------------------------

    override fun requestSync(accountId: String?) {
        scope.launch {
            val ids = accountId?.let { listOf(it) } ?: db.accountDao().getAll().map { it.id }
            for (id in ids) {
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(workDataOf(SyncWorker.KEY_ACCOUNT to id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                runCatching { workManager().enqueueUniqueWork("sync-$id", ExistingWorkPolicy.KEEP, request) }
            }
        }
    }

    override fun schedulePeriodic(intervalHours: Int, wifiOnly: Boolean) {
        scope.launch {
            val wm = runCatching { workManager() }.getOrNull() ?: return@launch
            if (db.accountDao().getAll().isEmpty()) {
                wm.cancelUniqueWork(PERIODIC_NAME)
                return@launch
            }
            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(intervalHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    // ---- sync ---------------------------------------------------------------------------------------------

    override suspend fun syncNow(accountId: String): SyncOutcome {
        val lock = locks.getOrPut(accountId) { Mutex() }
        return lock.withLock {
            try {
                val outcome = run(accountId)
                setState(accountId, if (outcome is SyncOutcome.Failure) SyncState.Failed(outcome.error, clock()) else SyncState.Idle)
                outcome
            } catch (e: CancellationException) {
                setState(accountId, SyncState.Idle)
                throw e
            }
        }
    }

    private suspend fun run(accountId: String): SyncOutcome {
        val account = db.accountDao().get(accountId) ?: return SyncOutcome.Failure(SyncError.UNKNOWN, retryable = false)
        return try {
            val api = apiFactory.forAccount(accountId)

            setState(accountId, SyncState.Running(SyncPhase.PUSH_LOCAL))
            // Server and server-app version (shown in the accounts, used for feature checks). A failed probe
            // does not stop the sync; a server without the E-Book Reader app does.
            val caps = try {
                api.capabilities()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (caps != null) {
                val app = caps.capabilities.ebookreader ?: return SyncOutcome.Failure(SyncError.APP_UNAVAILABLE, retryable = false)
                db.accountDao().updateVersions(accountId, caps.version?.string?.takeIf { it.isNotBlank() } ?: account.serverVersion, app.version)
            }
            edits.flushPending(accountId)
            progress.pushDirty(accountId)

            setState(accountId, SyncState.Running(SyncPhase.BOOKS))
            val stats = syncBooks(accountId, api, account.lastSyncCursor.orEmpty(), account.lastSyncAt)

            setState(accountId, SyncState.Running(SyncPhase.SHELVES))
            syncShelves(accountId, api)

            setState(accountId, SyncState.Running(SyncPhase.DOWNLOADS))
            enqueuePinned(accountId)
            downloads.resumePending()

            val now = clock()
            db.accountDao().get(accountId)?.let { db.accountDao().updateSync(accountId, it.lastSyncCursor, now) }
            SyncOutcome.Success(stats.changed, stats.deleted, stats.progressMerged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            android.util.Log.w(TAG, "sync failed: ${e.javaClass.simpleName} ${e.message}")
            SyncOutcome.Failure(mapError(e), retryable = isRetryable(e))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "sync failed", e)
            SyncOutcome.Failure(SyncError.UNKNOWN, retryable = true)
        }
    }

    private class Stats(var changed: Int = 0, var deleted: Int = 0, var progressMerged: Int = 0)

    private suspend fun syncBooks(accountId: String, api: EbookApi, startCursor: String, lastSyncAt: Long?): Stats {
        val stats = Stats()
        var cursor = startCursor
        val fullSync = cursor.isEmpty()
        val seen = HashSet<Long>()
        var resetOnce = false
        var finalFull = fullSync
        while (true) {
            val page: SyncDto = try {
                api.sync(cursor)
            } catch (e: ApiException.BadRequest) {
                if (cursor.isEmpty() || resetOnce) throw e
                // Invalid/expired cursor: forget it and start over with a full sync.
                resetOnce = true
                finalFull = true
                seen.clear()
                cursor = ""
                db.accountDao().updateSync(accountId, null, lastSyncAt)
                continue
            }
            if (page.hasMore && page.cursor == cursor) throw ApiException.Server(200, "Sync cursor did not advance")
            val staleKeys = commitPage(accountId, page, lastSyncAt, stats)
            page.books.forEach { seen += it.fileId }
            // After the page is safely stored: remove local files of deleted books, refresh changed files.
            for (id in page.deleted) downloads.delete(BookKey(accountId, id))
            db.bookDao().purgeDeleted(accountId)
            for ((key, pin) in staleKeys) downloads.enqueue(key, pin.first, pin.second)
            cursor = page.cursor
            if (!page.hasMore) break
        }
        if (finalFull) removeVanished(accountId, seen, stats)
        return stats
    }

    /** Writes one page + the new cursor atomically. Returns downloads whose file changed on the server. */
    private suspend fun commitPage(
        accountId: String,
        page: SyncDto,
        lastSyncAt: Long?,
        stats: Stats,
    ): List<Pair<BookKey, Pair<PinnedBy, String?>>> {
        val stale = ArrayList<Pair<BookKey, Pair<PinnedBy, String?>>>()
        db.withTransaction {
            val ids = page.books.map { it.fileId }
            val existing = db.bookDao().getByIds(accountId, ids).associateBy { it.fileId }
            val pendingBooks = ids.filter { db.pendingEditDao().countForBook(accountId, it) > 0 }.toSet()
            val entities = ArrayList<BookEntity>(page.books.size)
            val written = ArrayList<com.somecatcode.ebookreader.data.api.BookDto>(page.books.size)
            for (dto in page.books) {
                val old = existing[dto.fileId]
                if (dto.fileId in pendingBooks && old != null && !old.deleted) continue // local edit not yet uploaded
                val entity = dto.toEntity(accountId, old?.fileEtag)
                entities += entity
                written += dto
                if (old != null && (old.mtime != dto.mtime || old.size != dto.size)) {
                    db.downloadDao().get(accountId, dto.fileId)?.let { d ->
                        if (d.state == DownloadState.DONE.name) {
                            db.downloadDao().upsert(
                                d.copy(state = DownloadState.QUEUED.name, bytes = 0, total = dto.size, fileEtag = null, updatedAt = clock()),
                            )
                            stale += BookKey(accountId, dto.fileId) to (runCatching { PinnedBy.valueOf(d.pinnedBy) }.getOrDefault(PinnedBy.BOOK) to d.pinRef)
                        }
                    }
                }
            }
            if (entities.isNotEmpty()) db.bookDao().upsertAll(entities)
            // Tags reference the book row, so they are written after the books.
            for (dto in written) db.bookTagDao().replaceForBook(accountId, dto.fileId, dto.tagEntities(accountId))
            stats.changed += entities.size

            if (page.deleted.isNotEmpty()) {
                db.bookDao().markDeleted(accountId, page.deleted)
                db.bookTagDao().deleteForBooks(accountId, page.deleted)
                db.progressDao().deleteForBooks(accountId, page.deleted)
                db.pendingEditDao().deleteForBooks(accountId, page.deleted)
                stats.deleted += page.deleted.size
            }

            stats.progressMerged += mergeProgress(accountId, page)
            db.accountDao().updateSync(accountId, page.cursor, lastSyncAt)
        }
        return stale
    }

    private suspend fun mergeProgress(accountId: String, page: SyncDto): Int {
        var merged = 0
        val dao = db.progressDao()
        for (p in page.progress) {
            val local: ProgressEntity? = dao.get(accountId, p.fileId)
            // Rule: the larger clientUpdatedAt wins; a dirty local row only loses against a newer remote one.
            if (local != null && local.clientUpdatedAt > p.clientUpdatedAt) continue
            dao.upsert(p.toEntity(accountId))
            merged++
        }
        return merged
    }

    /** Full sync completed: local books the server did not list (and no tombstone told us about) are gone. */
    private suspend fun removeVanished(accountId: String, seen: Set<Long>, stats: Stats) {
        val vanished = db.bookDao().activeFileIds(accountId).filter { it !in seen }
        if (vanished.isEmpty()) return
        db.withTransaction {
            db.bookDao().markDeleted(accountId, vanished)
            db.bookTagDao().deleteForBooks(accountId, vanished)
            db.progressDao().deleteForBooks(accountId, vanished)
            db.pendingEditDao().deleteForBooks(accountId, vanished)
        }
        for (id in vanished) downloads.delete(BookKey(accountId, id))
        db.bookDao().purgeDeleted(accountId)
        stats.deleted += vanished.size
    }

    // ---- shelves ------------------------------------------------------------------------------------------

    /** Shelf list and the membership of all manual shelves (always reloaded: membership changes do not touch the shelf row). */
    private suspend fun syncShelves(accountId: String, api: EbookApi) = ShelfSync.refreshAll(db, api, accountId)

    // ---- pinned shelves / series -----------------------------------------------------------------------------

    private suspend fun enqueuePinned(accountId: String) {
        val rows = db.downloadDao().forAccount(accountId)
        val existing = rows.map { it.fileId }.toSet()
        val shelfRefs = rows.filter { it.pinnedBy == PinnedBy.SHELF.name }.mapNotNull { it.pinRef }.toSet()
        val seriesRefs = rows.filter { it.pinnedBy == PinnedBy.SERIES.name }.mapNotNull { it.pinRef }.toSet()
        for (ref in shelfRefs) {
            val shelfId = ref.toLongOrNull() ?: continue
            for (book in PinnedMembers.shelfBooks(db, accountId, shelfId)) {
                if (book.downloadable && book.fileId !in existing) downloads.enqueue(BookKey(accountId, book.fileId), PinnedBy.SHELF, ref)
            }
        }
        if (seriesRefs.isNotEmpty()) {
            val books = db.bookDao().getAllActive(accountId)
            for (name in seriesRefs) {
                for (book in books.filter { it.series == name && it.downloadable && it.fileId !in existing }) {
                    downloads.enqueue(BookKey(accountId, book.fileId), PinnedBy.SERIES, name)
                }
            }
        }
    }

    // ---- errors ------------------------------------------------------------------------------------------

    private fun setState(accountId: String, state: SyncState) {
        states.update { it + (accountId to state) }
    }

    private fun mapError(e: ApiException): SyncError = when (e) {
        is ApiException.Network -> SyncError.OFFLINE
        is ApiException.Unauthorized -> SyncError.UNAUTHORIZED
        is ApiException.NotFound -> SyncError.APP_UNAVAILABLE
        is ApiException.Server, is ApiException.RateLimited -> SyncError.SERVER
        is ApiException.Forbidden, is ApiException.BadRequest -> SyncError.UNKNOWN
    }

    private fun isRetryable(e: ApiException): Boolean = when (e) {
        is ApiException.Network, is ApiException.Server, is ApiException.RateLimited -> true
        else -> false
    }

    companion object {
        const val PERIODIC_NAME = "sync-periodic"
        private const val TAG = "SyncEngine"
    }
}
