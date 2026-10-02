package com.somecatcode.ebookreader.data.download

import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.data.repo.settingsOnce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Where book files live: `<root>/<accountId>/<fileId>.<format>` (+ `.part` while downloading). */
class DownloadStorage(private val rootProvider: () -> File) {
    val root: File get() = rootProvider()
    fun accountDir(accountId: String) = File(root, accountId)
    fun target(accountId: String, fileId: Long, format: String) = File(accountDir(accountId), "$fileId.${safeExt(format)}")
    fun part(target: File) = File(target.parentFile, target.name + ".part")

    private fun safeExt(format: String) = format.filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
}

/** Result of one worker run. */
sealed interface DownloadRun {
    data object Done : DownloadRun
    data object Gone : DownloadRun
    data class Retry(val reason: String) : DownloadRun
    data class Failed(val reason: String) : DownloadRun
}

/**
 * [DownloadManager] backed by the `download` table and one WorkManager unique work per book
 * (`download-<accountId>-<fileId>`). At most [MAX_PARALLEL] transfers run at the same time (a
 * process-wide semaphore; further workers wait in state QUEUED).
 */
class DownloadManagerImpl(
    private val db: AppDatabase,
    private val apiFactory: ApiClientFactory,
    private val settings: SettingsRepository,
    private val storage: DownloadStorage,
    private val workManager: () -> WorkManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxParallel: Int = MAX_PARALLEL,
) : DownloadManager {

    private val dao get() = db.downloadDao()
    private val semaphore = Semaphore(maxParallel)
    private val active = ConcurrentHashMap.newKeySet<BookKey>()

    // ---- DownloadManager ----------------------------------------------------------------------------

    override suspend fun enqueue(key: BookKey, pinnedBy: PinnedBy, pinRef: String?) {
        val book = db.bookDao().get(key.accountId, key.fileId)
        if (book == null || book.deleted || !book.downloadable) return
        val needsWork = db.withTransaction {
            val existing = dao.get(key.accountId, key.fileId)
            val pin = mergePin(existing, pinnedBy, pinRef)
            when {
                existing == null -> {
                    dao.upsert(newRow(key, book, pin.first, pin.second))
                    true
                }
                existing.state == DownloadState.DONE.name -> {
                    val file = existing.localPath?.let(::File)
                    if (file != null && file.isFile && existing.total == book.size) {
                        if (pin.first.name != existing.pinnedBy || pin.second != existing.pinRef) {
                            dao.upsert(existing.copy(pinnedBy = pin.first.name, pinRef = pin.second, updatedAt = clock()))
                        }
                        false
                    } else {
                        dao.upsert(newRow(key, book, pin.first, pin.second)) // vanished or changed: fetch again
                        true
                    }
                }
                else -> { // QUEUED, RUNNING, FAILED
                    val state = if (existing.state == DownloadState.RUNNING.name && key in active) existing.state else DownloadState.QUEUED.name
                    dao.upsert(
                        existing.copy(
                            state = state, pinnedBy = pin.first.name, pinRef = pin.second,
                            errorMessage = null, total = book.size, updatedAt = clock(),
                        ),
                    )
                    true
                }
            }
        }
        if (needsWork) scheduleWork(key)
    }

    override suspend fun cancel(key: BookKey) {
        workManager().cancelUniqueWork(workName(key))
        withContext(NonCancellable + Dispatchers.IO) {
            val row = dao.get(key.accountId, key.fileId)
            if (row != null && row.state != DownloadState.DONE.name) {
                dao.delete(key.accountId, key.fileId)
                book(key)?.let { storage.part(storage.target(key.accountId, key.fileId, it.format)).delete() }
            }
        }
    }

    override suspend fun delete(key: BookKey) {
        workManager().cancelUniqueWork(workName(key))
        withContext(NonCancellable + Dispatchers.IO) {
            val row = dao.get(key.accountId, key.fileId)
            val format = book(key)?.format
            row?.localPath?.let { File(it).delete(); File("$it.part").delete() }
            if (format != null) {
                val target = storage.target(key.accountId, key.fileId, format)
                target.delete()
                storage.part(target).delete()
            }
            dao.delete(key.accountId, key.fileId)
            db.bookDao().updateFileEtag(key.accountId, key.fileId, null)
        }
    }

    override suspend fun localFile(key: BookKey): File? = withContext(Dispatchers.IO) {
        val row = dao.get(key.accountId, key.fileId) ?: return@withContext null
        if (row.state != DownloadState.DONE.name) return@withContext null
        val file = row.localPath?.let(::File)
        if (file != null && file.isFile && file.length() > 0) {
            file
        } else {
            dao.delete(key.accountId, key.fileId) // the file vanished (cleared storage): forget it
            null
        }
    }

    override suspend fun resumePending() {
        val rows = dao.inStates(listOf(DownloadState.QUEUED.name, DownloadState.RUNNING.name, DownloadState.FAILED.name))
        for (row in rows) {
            val key = BookKey(row.accountId, row.fileId)
            if (key in active) continue
            val book = book(key)
            if (book == null || book.deleted || !book.downloadable) {
                dao.delete(row.accountId, row.fileId)
                continue
            }
            dao.upsert(row.copy(state = DownloadState.QUEUED.name, errorMessage = null, updatedAt = clock()))
            scheduleWork(key)
        }
    }

    override fun freeBytes(): Long {
        var dir: File? = storage.root
        while (dir != null && !dir.exists()) dir = dir.parentFile
        return dir?.usableSpace ?: 0L
    }

    /** Cancels all downloads of an account (before the account is removed). */
    suspend fun cancelAccount(accountId: String) {
        for (row in dao.forAccount(accountId)) workManager().cancelUniqueWork(workName(BookKey(accountId, row.fileId)))
    }

    // ---- worker side ----------------------------------------------------------------------------------

    /**
     * Runs one download to completion; called by [DownloadWorker]. [onProgress] is invoked
     * (throttled) with the bytes so far and the total, e.g. to update a foreground notification.
     */
    suspend fun runDownload(
        key: BookKey,
        attempt: Int,
        onProgress: suspend (title: String, bytes: Long, total: Long) -> Unit = { _, _, _ -> },
    ): DownloadRun {
        val row = dao.get(key.accountId, key.fileId) ?: return DownloadRun.Gone
        if (row.state == DownloadState.DONE.name && row.localPath?.let { File(it).isFile } == true) return DownloadRun.Done
        val book = book(key)
        if (book == null || book.deleted) {
            dao.delete(key.accountId, key.fileId)
            return DownloadRun.Gone
        }
        if (!book.downloadable) return fail(key, "Not downloadable")

        return semaphore.withPermit {
            active += key
            try {
                setState(key, DownloadState.RUNNING, null)
                val outcome = try {
                    transfer(key, book, onProgress)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        // Stopped (constraints lost, process stop): the partial file is kept for resuming.
                        if (dao.get(key.accountId, key.fileId) != null) setState(key, DownloadState.QUEUED, null)
                    }
                    throw e
                }
                when (outcome) {
                    is DownloadRun.Retry ->
                        if (attempt >= MAX_ATTEMPTS) fail(key, outcome.reason) else {
                            setState(key, DownloadState.QUEUED, outcome.reason)
                            outcome
                        }
                    is DownloadRun.Failed -> fail(key, outcome.reason)
                    else -> outcome
                }
            } finally {
                active -= key
            }
        }
    }

    private suspend fun transfer(
        key: BookKey,
        book: BookEntity,
        onProgress: suspend (String, Long, Long) -> Unit,
    ): DownloadRun = withContext(Dispatchers.IO) {
        val account = db.accountDao().get(key.accountId) ?: return@withContext DownloadRun.Gone
        val api = try {
            apiFactory.forAccount(key.accountId)
        } catch (e: ApiException.Unauthorized) {
            return@withContext DownloadRun.Failed("unauthorized")
        }
        val url = api.davFileUrl(account.userId, book.path)
        val target = storage.target(key.accountId, key.fileId, book.format)
        val part = storage.part(target)
        target.parentFile?.mkdirs()
        val title = book.title?.takeIf { it.isNotBlank() } ?: book.path.substringAfterLast('/')

        var row = dao.get(key.accountId, key.fileId) ?: return@withContext DownloadRun.Gone
        var restarts = 0
        while (true) {
            ensureActive()
            val partSize = if (part.isFile) part.length() else 0L
            val plan = ResumePolicy.plan(partSize, row.fileEtag, book.size)
            if (plan.discardPart) {
                part.delete()
                row = row.copy(fileEtag = null)
            }
            val request = Request.Builder().url(url).get().apply {
                if (plan.rangeFrom != null) {
                    header("Range", "bytes=${plan.rangeFrom}-")
                    plan.ifRange?.let { header("If-Range", it) }
                }
                header("Accept-Encoding", "identity")
            }.build()

            val response = try {
                api.http.execute(request)
            } catch (e: IOException) {
                return@withContext DownloadRun.Retry(e.message ?: "network")
            }
            response.use { r ->
                val currentPart = if (part.isFile) part.length() else 0L
                val contentRange = ResumePolicy.parseContentRange(r.header("Content-Range"))
                when (val action = ResumePolicy.interpret(r.code, plan.rangeFrom, contentRange?.first, currentPart, book.size)) {
                    is ResumePolicy.ResponseAction.Fail -> {
                        return@withContext when {
                            r.code == 401 -> DownloadRun.Failed("unauthorized")
                            r.code == 403 -> DownloadRun.Failed("forbidden")
                            r.code == 404 -> DownloadRun.Failed("not found")
                            action.retryable -> DownloadRun.Retry("HTTP ${r.code}")
                            else -> DownloadRun.Failed("HTTP ${r.code}")
                        }
                    }
                    ResumePolicy.ResponseAction.AlreadyComplete -> {
                        return@withContext finish(key, book, target, part, row.fileEtag, currentPart)
                    }
                    ResumePolicy.ResponseAction.Restart, ResumePolicy.ResponseAction.Append -> {
                        val append = action == ResumePolicy.ResponseAction.Append
                        val body = r.body
                        val etag = ResumePolicy.usableEtag(r.header("ETag")) ?: if (append) row.fileEtag else null
                        val offset = if (append) currentPart else 0L
                        val total = when {
                            append -> contentRange?.second ?: (offset + body.contentLength().coerceAtLeast(0))
                            else -> body.contentLength().takeIf { it > 0 } ?: book.size
                        }
                        if (total > 0 && freeBytes() < (total - offset) + SAFETY_MARGIN) {
                            return@withContext DownloadRun.Failed("Not enough storage")
                        }
                        row = row.copy(total = total, fileEtag = etag, localPath = part.absolutePath)
                        dao.upsert(row.copy(state = DownloadState.RUNNING.name, bytes = offset, updatedAt = clock()))
                        val written = try {
                            copyBody(key, body.byteStream(), part, append, offset, total, title, onProgress)
                        } catch (e: IOException) {
                            val msg = e.message.orEmpty()
                            return@withContext if (msg.contains("ENOSPC") || msg.contains("No space", true)) {
                                DownloadRun.Failed("Not enough storage")
                            } else {
                                DownloadRun.Retry(msg.ifEmpty { "network" })
                            }
                        }
                        if (total > 0 && written != total) {
                            // Short body (connection dropped or lying server): resume if we may, else start over.
                            return@withContext if (restarts++ < 2 && etag != null) continue else DownloadRun.Retry("incomplete")
                        }
                        return@withContext finish(key, book, target, part, etag, written)
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        DownloadRun.Retry("unreachable")
    }

    private suspend fun copyBody(
        key: BookKey,
        input: java.io.InputStream,
        part: File,
        append: Boolean,
        offset: Long,
        total: Long,
        title: String,
        onProgress: suspend (String, Long, Long) -> Unit,
    ): Long {
        var written = offset
        var lastDb = 0L
        var lastNotify = 0L
        val buffer = ByteArray(BUFFER)
        FileOutputStream(part, append).use { out ->
            input.use { ins ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = ins.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    val now = clock()
                    if (now - lastDb >= DB_INTERVAL_MS) {
                        lastDb = now
                        dao.updateProgress(key.accountId, key.fileId, written, total, DownloadState.RUNNING.name, now)
                    }
                    if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                        lastNotify = now
                        onProgress(title, written, total)
                    }
                }
                out.flush()
                out.fd.sync()
            }
        }
        return written
    }

    private suspend fun finish(key: BookKey, book: BookEntity, target: File, part: File, etag: String?, size: Long): DownloadRun {
        if (size <= 0) {
            part.delete()
            return DownloadRun.Retry("empty file")
        }
        moveAtomically(part, target)
        withContext(NonCancellable) {
            val row = dao.get(key.accountId, key.fileId)
            if (row != null) {
                dao.upsert(
                    row.copy(
                        state = DownloadState.DONE.name, bytes = size, total = size, localPath = target.absolutePath,
                        fileEtag = etag, errorMessage = null, updatedAt = clock(),
                    ),
                )
                db.bookDao().updateFileEtag(key.accountId, key.fileId, etag)
            }
        }
        return DownloadRun.Done
    }

    private fun moveAtomically(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private suspend fun book(key: BookKey) = db.bookDao().get(key.accountId, key.fileId)

    private suspend fun setState(key: BookKey, state: DownloadState, error: String?) {
        dao.updateState(key.accountId, key.fileId, state.name, error, clock())
    }

    private suspend fun fail(key: BookKey, reason: String): DownloadRun {
        withContext(NonCancellable) { setState(key, DownloadState.FAILED, reason) }
        return DownloadRun.Failed(reason)
    }

    private fun newRow(key: BookKey, book: BookEntity, pinnedBy: PinnedBy, pinRef: String?) = DownloadEntity(
        accountId = key.accountId, fileId = key.fileId, state = DownloadState.QUEUED.name, bytes = 0, total = book.size,
        localPath = null, fileEtag = null, pinnedBy = pinnedBy.name, pinRef = pinRef, errorMessage = null, updatedAt = clock(),
    )

    /** A single book pin outranks shelf and series pins; otherwise the existing pin stays. */
    private fun mergePin(existing: DownloadEntity?, pinnedBy: PinnedBy, pinRef: String?): Pair<PinnedBy, String?> {
        val current = existing?.let { runCatching { PinnedBy.valueOf(it.pinnedBy) }.getOrNull() } ?: return pinnedBy to pinRef
        return when {
            current == PinnedBy.BOOK -> current to existing.pinRef
            pinnedBy == PinnedBy.BOOK -> pinnedBy to pinRef
            else -> current to existing.pinRef
        }
    }

    private suspend fun scheduleWork(key: BookKey) {
        val wifiOnly = settings.settingsOnce().downloadOnlyOnWifi
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ACCOUNT to key.accountId, DownloadWorker.KEY_FILE to key.fileId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_DOWNLOAD)
            .build()
        workManager().enqueueUniqueWork(workName(key), ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val MAX_PARALLEL = 2
        const val MAX_ATTEMPTS = 5
        const val TAG_DOWNLOAD = "download"
        private const val BUFFER = 64 * 1024
        private const val DB_INTERVAL_MS = 500L
        private const val NOTIFY_INTERVAL_MS = 1000L
        private const val SAFETY_MARGIN = 8L * 1024 * 1024

        fun workName(key: BookKey) = "download-${key.accountId}-${key.fileId}"
    }
}
