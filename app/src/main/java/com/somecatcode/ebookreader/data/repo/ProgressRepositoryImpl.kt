package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.api.ProgressBatchItem
import com.somecatcode.ebookreader.data.api.ProgressPutRequest
import com.somecatcode.ebookreader.data.api.ProgressPutResult
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.ProgressEntity
import com.somecatcode.ebookreader.data.sync.LocalChangesScheduler
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * Reading progress: local first (Room, `dirty` until uploaded), upload through `/progress/batch`.
 * Conflict rule (CONTRACTS section 5): the larger `clientUpdatedAt` wins; the dialog is only shown
 * when the server position is newer AND differs noticeably.
 */
class ProgressRepositoryImpl(
    private val db: AppDatabase,
    private val apiFactory: ApiClientFactory,
    private val settings: SettingsRepository,
    private val scheduler: LocalChangesScheduler,
    private val defaultDeviceName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressRepository {

    private val dao get() = db.progressDao()

    override fun progress(key: BookKey): Flow<StoredProgress?> =
        dao.observe(key.accountId, key.fileId).map { it?.toStored() }

    override suspend fun saveLocal(key: BookKey, locator: Locator, percentage: Double) {
        val device = deviceName()
        val now = clock()
        db.withTransaction {
            val existing = dao.get(key.accountId, key.fileId)
            // Never let the clock go backwards for the same book (conflict rule uses the timestamp).
            val stamp = maxOf(now, (existing?.clientUpdatedAt ?: 0L) + 1)
            dao.upsert(
                ProgressEntity(
                    accountId = key.accountId,
                    fileId = key.fileId,
                    locator = ApiJson.encodeToString(Locator.serializer(), locator),
                    percentage = percentage.coerceIn(0.0, 1.0),
                    device = device,
                    clientUpdatedAt = stamp,
                    updatedAt = existing?.updatedAt ?: 0L,
                    dirty = true,
                ),
            )
            // The server derives the status from the progress; mirror it so the library is right offline.
            db.bookDao().get(key.accountId, key.fileId)?.let { book ->
                // Like the server: a book set to "reading" by hand stays "reading" at its first page.
                val status = if (percentage <= 0.0 && book.readStatus == "reading") "reading" else statusFor(percentage)
                if (status != book.readStatus) db.bookDao().updateAppData(key.accountId, key.fileId, book.rating, status)
            }
        }
        scheduler.schedule(key.accountId)
    }

    override suspend fun checkRemote(key: BookKey): ProgressConflict? {
        val remote = try {
            apiFactory.forAccount(key.accountId).progress(key.fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // offline or failing server: open at the local position
        } ?: return null

        val local = dao.get(key.accountId, key.fileId)
        val localLocator = local?.decodeLocator()
        if (local == null || localLocator == null) {
            // Nothing local to compare with: adopt the server position silently.
            dao.upsert(remote.toEntity(key.accountId))
            return null
        }
        if (remote.clientUpdatedAt <= local.clientUpdatedAt) return null // local wins (uploaded later)
        if (!differsNoticeably(localLocator, local.percentage, remote.locator, remote.percentage)) {
            if (!local.dirty) dao.upsert(remote.toEntity(key.accountId))
            return null
        }
        return ProgressConflict(
            key = key,
            localLocator = localLocator,
            localPercentage = local.percentage,
            remoteLocator = remote.locator,
            remotePercentage = remote.percentage,
            remoteDevice = remote.device,
            remoteUpdatedAt = remote.updatedAt,
        )
    }

    override suspend fun acceptRemote(conflict: ProgressConflict) {
        val key = conflict.key
        db.withTransaction {
            dao.upsert(
                ProgressEntity(
                    accountId = key.accountId,
                    fileId = key.fileId,
                    locator = ApiJson.encodeToString(Locator.serializer(), conflict.remoteLocator),
                    percentage = conflict.remotePercentage,
                    device = conflict.remoteDevice,
                    // The conflict carries the server time; it is >= the remote clientUpdatedAt, so the
                    // accepted position is never reported as "newer remote" again.
                    clientUpdatedAt = conflict.remoteUpdatedAt,
                    updatedAt = conflict.remoteUpdatedAt,
                    dirty = false,
                ),
            )
        }
    }

    override suspend fun keepLocal(conflict: ProgressConflict) {
        val key = conflict.key
        val updated = db.withTransaction {
            val local = dao.get(key.accountId, key.fileId)
            val base = local ?: ProgressEntity(
                key.accountId, key.fileId,
                ApiJson.encodeToString(Locator.serializer(), conflict.localLocator),
                conflict.localPercentage, deviceName(), 0, 0, true,
            )
            base.copy(clientUpdatedAt = maxOf(clock(), conflict.remoteUpdatedAt + 1), device = deviceName(), dirty = true)
                .also { dao.upsert(it) }
        }
        val locator = updated.decodeLocator() ?: return
        try {
            val api = apiFactory.forAccount(key.accountId)
            val result = api.putProgress(
                key.fileId,
                ProgressPutRequest(locator, updated.percentage, updated.device, updated.clientUpdatedAt),
            )
            if (result is ProgressPutResult.Stored) markUploaded(updated, result.progress.updatedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            scheduler.schedule(key.accountId) // stays dirty, uploaded later
        }
    }

    override suspend fun pushDirty(accountId: String) {
        val rows = dao.dirty(accountId)
        if (rows.isEmpty()) return
        val sent = HashMap<Long, ProgressEntity>()
        val items = ArrayList<ProgressBatchItem>()
        for (row in rows) {
            val locator = row.decodeLocator()
            if (locator == null) { // unreadable row: drop the dirty flag, it can never be uploaded
                dao.upsert(row.copy(dirty = false))
                continue
            }
            sent[row.fileId] = row
            items += ProgressBatchItem(row.fileId, locator, row.percentage, row.device, row.clientUpdatedAt)
        }
        if (items.isEmpty()) return
        val results = try {
            apiFactory.forAccount(accountId).putProgressBatch(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return // retried by the next sync / worker
        }
        for (result in results) {
            val row = sent[result.fileId] ?: continue
            when (result.status) {
                "ok" -> markUploaded(row, result.progress?.updatedAt ?: row.updatedAt)
                "conflict" -> result.progress?.let { server ->
                    // Server holds a newer position: take it unless the user moved on meanwhile.
                    db.withTransaction {
                        val cur = dao.get(accountId, row.fileId)
                        if (cur != null && cur.clientUpdatedAt == row.clientUpdatedAt) dao.upsert(server.toEntity(accountId))
                    }
                }
                else -> db.withTransaction { // permanent per-item error (book gone, invalid): stop retrying
                    val cur = dao.get(accountId, row.fileId)
                    if (cur != null && cur.clientUpdatedAt == row.clientUpdatedAt) dao.upsert(cur.copy(dirty = false))
                }
            }
        }
    }

    private suspend fun markUploaded(sent: ProgressEntity, serverUpdatedAt: Long) {
        db.withTransaction {
            val cur = dao.get(sent.accountId, sent.fileId)
            if (cur != null && cur.clientUpdatedAt == sent.clientUpdatedAt) {
                dao.upsert(cur.copy(dirty = false, updatedAt = serverUpdatedAt))
            }
        }
    }

    private suspend fun deviceName(): String =
        settings.settingsOnce().deviceName?.takeIf { it.isNotBlank() } ?: defaultDeviceName()

    private fun differsNoticeably(a: Locator, pa: Double, b: Locator, pb: Double): Boolean =
        a.href != b.href || abs(pa - pb) > NOTICEABLE

    private companion object {
        const val NOTICEABLE = 0.01
    }
}

/** Read status implied by a reading position (server `ProgressService::FINISHED_THRESHOLD` = 0.98). */
internal fun statusFor(percentage: Double): String = when {
    percentage >= FINISHED_THRESHOLD -> "finished"
    percentage > 0.0 -> "reading"
    else -> "unread"
}

internal const val FINISHED_THRESHOLD = 0.98
