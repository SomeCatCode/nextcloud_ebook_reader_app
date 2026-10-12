package com.somecatcode.ebookreader.data.transfer

import androidx.room.withTransaction
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.db.AccountKind
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.download.DownloadManager
import com.somecatcode.ebookreader.data.local.BookFile
import com.somecatcode.ebookreader.data.local.LocalLibrary
import com.somecatcode.ebookreader.data.local.asBookFile
import com.somecatcode.ebookreader.data.repo.AnnotationRepository
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.EditRepository
import com.somecatcode.ebookreader.data.repo.ProgressRepository
import com.somecatcode.ebookreader.data.sync.SyncEngine
import com.somecatcode.ebookreader.reader.ReaderRequestProxyImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Result of a copy or move. [stateCopied] = reading progress and highlights went along. */
data class TransferResult(val target: BookKey, val stateCopied: Boolean)

/** Why a transfer failed; [copied] = the copy exists (a move could not delete the original). */
class TransferException(val reason: Reason, val copied: BookKey? = null, cause: Throwable? = null) :
    Exception(reason.name, cause) {
    enum class Reason { NOT_ALLOWED, SOURCE_UNAVAILABLE, TARGET_FAILED, DELETE_FAILED }
}

/**
 * Copies or moves a book to another account: a Nextcloud account (upload into one of its library
 * folders, the server indexes it) or a local library (a new file in the folder). Reading progress,
 * read status, rating and highlights go along with new ids. A move deletes the original afterwards
 * (Nextcloud: into the trash bin), but only after the copy succeeded.
 */
interface BookTransfer {
    /** Library folders of a Nextcloud account (first = default); empty for local libraries. */
    suspend fun folders(accountId: String): List<String>

    /** @param folder target folder of a Nextcloud account; null = its first library folder */
    suspend fun transfer(source: BookKey, targetAccountId: String, folder: String?, move: Boolean): TransferResult
}

class BookTransferImpl(
    private val db: AppDatabase,
    private val apiFactory: ApiClientFactory,
    private val localLibrary: LocalLibrary,
    private val downloads: DownloadRepository,
    private val downloadManager: () -> DownloadManager,
    private val syncEngine: () -> SyncEngine,
    private val progress: () -> ProgressRepository,
    private val annotations: () -> AnnotationRepository,
    private val edits: () -> EditRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val indexWaitMs: Long = 2_000,
) : BookTransfer {

    override suspend fun folders(accountId: String): List<String> {
        if (db.accountDao().get(accountId)?.kind != AccountKind.NEXTCLOUD) return emptyList()
        return runCatching { apiFactory.forAccount(accountId).libraryFolders() }.getOrDefault(emptyList())
            .map(::normalizeFolder).distinct()
    }

    override suspend fun transfer(source: BookKey, targetAccountId: String, folder: String?, move: Boolean): TransferResult {
        val book = db.bookDao().get(source.accountId, source.fileId)?.takeIf { !it.deleted }
            ?: throw TransferException(TransferException.Reason.SOURCE_UNAVAILABLE)
        val sourceAccount = db.accountDao().get(source.accountId) ?: throw TransferException(TransferException.Reason.SOURCE_UNAVAILABLE)
        val target = db.accountDao().get(targetAccountId) ?: throw TransferException(TransferException.Reason.TARGET_FAILED)
        // a view-only share cannot be read; a file of another user is not ours to delete
        if (targetAccountId == source.accountId || !book.downloadable || (move && book.shared)) {
            throw TransferException(TransferException.Reason.NOT_ALLOWED)
        }
        val fileName = book.path.substringAfterLast('/').ifBlank { "${book.title ?: "book"}.${book.format}" }

        val file = openSource(book, sourceAccount.kind, sourceAccount.userId)
        val newKey = try {
            if (target.kind == AccountKind.LOCAL) {
                localLibrary.importFile(targetAccountId, fileName) { out -> file.open(0, file.length).use { it.copyTo(out) } }
            } else {
                upload(targetAccountId, target.userId, folder, fileName, file)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) {
            throw TransferException(TransferException.Reason.TARGET_FAILED, cause = e)
        }

        val copied = newKey != null && copyState(source, book, newKey, target.kind == AccountKind.LOCAL)
        val result = newKey ?: BookKey(targetAccountId, 0)

        if (move) {
            try {
                if (sourceAccount.kind == AccountKind.LOCAL) {
                    localLibrary.deleteBook(source)
                } else {
                    apiFactory.forAccount(source.accountId).deleteFile(sourceAccount.userId, book.path)
                    forgetBook(source)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw TransferException(TransferException.Reason.DELETE_FAILED, copied = newKey, cause = e)
            }
        }
        return TransferResult(result, copied)
    }

    // ---- source --------------------------------------------------------------------------------

    /** Local file, finished download, or a WebDAV stream of the Nextcloud file. */
    private suspend fun openSource(book: BookEntity, kind: String, userId: String): BookFile {
        val key = BookKey(book.accountId, book.fileId)
        if (kind == AccountKind.LOCAL) {
            return localLibrary.bookFile(key) ?: throw TransferException(TransferException.Reason.SOURCE_UNAVAILABLE)
        }
        downloads.localFile(key)?.takeIf { it.isFile && it.length() == book.size }?.let { return it.asBookFile() }
        val api = try {
            apiFactory.forAccount(book.accountId)
        } catch (e: Exception) {
            throw TransferException(TransferException.Reason.SOURCE_UNAVAILABLE, cause = e)
        }
        val url = api.davFileUrl(userId, book.path)
        return object : BookFile {
            override val name = book.path.substringAfterLast('/')
            override val length = book.size
            override fun open(offset: Long, count: Long): InputStream {
                val response = api.http.execute(Request.Builder().url(url).get().build())
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("Download HTTP ${response.code}")
                }
                return object : FilterInputStream(response.body.byteStream()) {
                    override fun close() {
                        super.close()
                        response.close()
                    }
                }
            }
        }
    }

    // ---- Nextcloud target ------------------------------------------------------------------------

    /** Uploads into [folder] under a free name and waits for the server to index the book. */
    private suspend fun upload(accountId: String, userId: String, folder: String?, fileName: String, file: BookFile): BookKey? {
        val api = apiFactory.forAccount(accountId)
        val dir = folder?.let(::normalizeFolder) ?: api.libraryFolders().firstOrNull()?.let(::normalizeFolder) ?: "/"
        val path = freePath(dir, fileName) { api.fileExists(userId, it) }
        val fileId = withContext(Dispatchers.IO) {
            api.uploadFile(userId, path, file.length, ReaderRequestProxyImpl.mimeFor(fileName)) { file.open(0, file.length) }
        }
        // the server indexes new files at once (large ones in a background job): pick the book up with a sync
        repeat(INDEX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(indexWaitMs)
            runCatching { syncEngine().syncNow(accountId) }
            val row = fileId?.let { db.bookDao().get(accountId, it) } ?: db.bookDao().byPath(accountId, path)
            if (row != null && !row.deleted) return BookKey(accountId, row.fileId)
        }
        return null
    }

    // ---- state -----------------------------------------------------------------------------------

    /** Progress, status, rating and highlights of the source for the copy. Best effort: false when nothing could be written. */
    private suspend fun copyState(source: BookKey, book: BookEntity, target: BookKey, targetLocal: Boolean): Boolean = try {
        val now = clock()
        val sourceProgress = db.progressDao().get(source.accountId, source.fileId)
        val notes = db.annotationDao().allForBook(source.accountId, source.fileId).filter { !it.deleted }
        db.withTransaction {
            sourceProgress?.let { p ->
                db.progressDao().upsert(
                    p.copy(accountId = target.accountId, fileId = target.fileId, clientUpdatedAt = now, updatedAt = 0, dirty = !targetLocal),
                )
            }
            for (a in notes) {
                db.annotationDao().upsert(
                    a.copy(
                        accountId = target.accountId, uuid = newUuid().lowercase(), fileId = target.fileId,
                        updatedAt = 0, clientUpdatedAt = now, deleted = false, dirty = !targetLocal,
                    ),
                )
            }
        }
        val status = runCatching { ReadStatus.valueOf(book.readStatus.uppercase()) }.getOrNull()
        if (book.rating != null || (status != null && status != ReadStatus.UNREAD)) {
            edits().editAppData(target, AppDataPatch(setRating = book.rating != null, rating = book.rating, readStatus = status))
        }
        if (!targetLocal) {
            // upload right away; on failure the regular push picks the dirty rows up later
            runCatching { progress().pushDirty(target.accountId) }
            runCatching { annotations().pushDirty(target.accountId) }
            runCatching { edits().flushPending(target.accountId) }
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** The Nextcloud original is deleted: drop it here as the sync would. */
    private suspend fun forgetBook(key: BookKey) {
        val ids = listOf(key.fileId)
        db.withTransaction {
            db.bookDao().markDeleted(key.accountId, ids)
            db.bookTagDao().deleteForBooks(key.accountId, ids)
            db.progressDao().deleteForBooks(key.accountId, ids)
            db.pendingEditDao().deleteForBooks(key.accountId, ids)
            db.annotationDao().deleteForBooks(key.accountId, ids)
        }
        runCatching { downloadManager().delete(key) }
        db.bookDao().purgeDeleted(key.accountId)
    }

    companion object {
        private const val INDEX_ATTEMPTS = 4

        /** `Books/` -> `/Books`, `` -> `/`. */
        fun normalizeFolder(folder: String): String = "/" + folder.trim().trim('/')

        /** `dir/name.ext`, or `dir/name (2).ext`, ... when taken. */
        suspend fun freePath(dir: String, fileName: String, exists: suspend (String) -> Boolean): String {
            val base = fileName.substringBeforeLast('.', fileName)
            val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty() || it == fileName) "" else ".$it" }
            val prefix = if (dir == "/") "" else dir
            for (n in 1..MAX_NAMES) {
                val candidate = "$prefix/" + if (n == 1) fileName else "$base ($n)$ext"
                if (!exists(candidate)) return candidate
            }
            throw IOException("No free file name for $fileName")
        }

        private const val MAX_NAMES = 50
    }
}
