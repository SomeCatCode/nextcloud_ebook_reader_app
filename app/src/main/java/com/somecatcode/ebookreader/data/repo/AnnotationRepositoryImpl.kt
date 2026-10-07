package com.somecatcode.ebookreader.data.repo

import androidx.room.withTransaction
import com.somecatcode.ebookreader.data.api.AnnotationDto
import com.somecatcode.ebookreader.data.api.AnnotationUpsertRequest
import com.somecatcode.ebookreader.data.api.AnnotationWriteResult
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.db.AnnotationDao
import com.somecatcode.ebookreader.data.db.AnnotationEntity
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.sync.LocalChangesScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * [AnnotationRepository] on Room + `/books/{id}/annotations`. Uploads use the upsert (`POST` with the full
 * state and the uuid), which creates, updates and revives in one call; deletions use `DELETE` with the
 * tombstone's `clientUpdatedAt`. Every result is only applied when the row did not change meanwhile.
 */
class AnnotationRepositoryImpl(
    private val db: AppDatabase,
    private val apiFactory: ApiClientFactory,
    private val scheduler: LocalChangesScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) : AnnotationRepository {

    private val dao: AnnotationDao get() = db.annotationDao()

    override fun annotations(key: BookKey): Flow<List<BookAnnotation>> =
        dao.observeForBook(key.accountId, key.fileId).map { rows ->
            rows.mapNotNull { it.toModel() }.sortedWith(AnnotationOrder)
        }

    override suspend fun create(key: BookKey, locator: Locator, text: String?, color: AnnotationColor, note: String?): String {
        val uuid = newUuid().lowercase()
        val now = clock()
        val cleanNote = note?.takeIf { it.isNotBlank() }?.let { cut(it, ANNOTATION_MAX_NOTE) }
        dao.upsert(
            AnnotationEntity(
                accountId = key.accountId,
                uuid = uuid,
                fileId = key.fileId,
                type = if (cleanNote != null) AnnotationType.NOTE.wire else AnnotationType.HIGHLIGHT.wire,
                locator = ApiJson.encodeToString(Locator.serializer(), locator),
                text = text?.takeIf { it.isNotBlank() }?.let { cut(it, ANNOTATION_MAX_TEXT) },
                note = cleanNote,
                color = color.wire,
                createdAt = now,
                updatedAt = 0,
                clientUpdatedAt = now,
                deleted = false,
                dirty = true,
            ),
        )
        scheduler.schedule(key.accountId)
        return uuid
    }

    override suspend fun setNote(key: BookKey, uuid: String, note: String?) =
        change(key, uuid) { it.copy(note = note?.takeIf { n -> n.isNotBlank() }?.let { n -> cut(n, ANNOTATION_MAX_NOTE) }) }

    override suspend fun setColor(key: BookKey, uuid: String, color: AnnotationColor) =
        change(key, uuid) { it.copy(color = color.wire) }

    override suspend fun delete(key: BookKey, uuid: String) = change(key, uuid) { it.copy(deleted = true) }

    private suspend fun change(key: BookKey, uuid: String, transform: (AnnotationEntity) -> AnnotationEntity) {
        val changed = db.withTransaction {
            val row = dao.get(key.accountId, uuid) ?: return@withTransaction false
            if (row.deleted) return@withTransaction false
            val stamp = maxOf(clock(), row.clientUpdatedAt + 1) // the clock never goes backwards for a row
            dao.upsert(transform(row).copy(clientUpdatedAt = stamp, dirty = true))
            true
        }
        if (changed) scheduler.schedule(key.accountId)
    }

    override suspend fun refresh(key: BookKey) {
        val remote = try {
            apiFactory.forAccount(key.accountId).annotations(key.fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // offline, old server or no access: the local state stays
        }
        db.withTransaction {
            val listed = remote.mapTo(HashSet()) { it.uuid.lowercase() }
            for (row in dao.allForBook(key.accountId, key.fileId)) {
                // The list has no tombstones: a clean row missing from it was deleted on the server.
                if (!row.dirty && row.updatedAt > 0 && row.uuid !in listed) dao.delete(key.accountId, row.uuid)
            }
            for (dto in remote) mergeServerAnnotation(dao, key.accountId, dto)
        }
    }

    override suspend fun pushDirty(accountId: String) {
        val rows = dao.dirty(accountId)
        if (rows.isEmpty()) return
        val api = try {
            apiFactory.forAccount(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return
        }
        for (row in rows) {
            try {
                if (row.deleted) {
                    when (val result = api.deleteAnnotation(row.uuid, row.clientUpdatedAt)) {
                        is AnnotationWriteResult.Stored -> applyIfUnchanged(row) { dao.delete(accountId, row.uuid) }
                        is AnnotationWriteResult.Conflict -> applyIfUnchanged(row) { storeServer(accountId, result.current) }
                    }
                } else {
                    val locator = row.decodeLocator()
                    if (locator == null) { // unreadable row: can never be uploaded
                        applyIfUnchanged(row) { dao.upsert(row.copy(dirty = false)) }
                        continue
                    }
                    val body = AnnotationUpsertRequest(
                        uuid = row.uuid, type = row.type, locator = locator, text = row.text, note = row.note,
                        color = row.color, clientUpdatedAt = row.clientUpdatedAt, createdAt = row.createdAt,
                    )
                    when (val result = api.upsertAnnotation(row.fileId, body)) {
                        is AnnotationWriteResult.Stored -> applyIfUnchanged(row) { storeServer(accountId, result.annotation) }
                        is AnnotationWriteResult.Conflict -> applyIfUnchanged(row) { storeServer(accountId, result.current) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.NotFound) {
                if (row.deleted) {
                    applyIfUnchanged(row) { dao.delete(accountId, row.uuid) } // never reached the server
                } else if (!bookExists(accountId, row.fileId)) {
                    applyIfUnchanged(row) { dao.delete(accountId, row.uuid) } // the book is gone
                } else {
                    return // book known but endpoint missing (server without annotations): retry later
                }
            } catch (e: ApiException.BadRequest) {
                applyIfUnchanged(row) { dao.upsert(row.copy(dirty = false)) } // rejected for good: keep it local only
            } catch (e: ApiException.Forbidden) {
                applyIfUnchanged(row) { dao.upsert(row.copy(dirty = false)) } // view-only share: annotations stay local
            } catch (e: ApiException) {
                return // offline, server error, rate limit, auth: retried by the next push/sync
            }
        }
    }

    private suspend fun bookExists(accountId: String, fileId: Long): Boolean =
        db.bookDao().get(accountId, fileId)?.deleted == false

    /** Applies a server answer only if the user did not change the row while the request was running. */
    private suspend fun applyIfUnchanged(sent: AnnotationEntity, block: suspend () -> Unit) {
        db.withTransaction {
            val cur = dao.get(sent.accountId, sent.uuid) ?: return@withTransaction
            if (cur.clientUpdatedAt == sent.clientUpdatedAt && cur.deleted == sent.deleted) block()
        }
    }

    private suspend fun storeServer(accountId: String, dto: AnnotationDto) {
        if (dto.deleted) dao.delete(accountId, dto.uuid.lowercase()) else dao.upsert(dto.toEntity(accountId))
    }
}

/**
 * Applies one annotation of the server (delta sync or list) by uuid: a tombstone removes the local row,
 * otherwise the server row replaces the local one unless a local change is newer (`clientUpdatedAt`).
 * Returns true when the local state changed.
 */
internal suspend fun mergeServerAnnotation(dao: AnnotationDao, accountId: String, dto: AnnotationDto): Boolean {
    val uuid = dto.uuid.lowercase()
    val local = dao.get(accountId, uuid)
    if (local != null && local.dirty && local.clientUpdatedAt > dto.clientUpdatedAt) return false
    if (dto.deleted) {
        if (local == null) return false
        dao.delete(accountId, uuid)
        return true
    }
    dao.upsert(dto.toEntity(accountId))
    return true
}

internal fun AnnotationDto.toEntity(accountId: String) = AnnotationEntity(
    accountId = accountId,
    uuid = uuid.lowercase(),
    fileId = fileId,
    type = type,
    locator = ApiJson.encodeToString(Locator.serializer(), locator),
    text = text,
    note = note,
    color = color,
    createdAt = createdAt,
    updatedAt = updatedAt,
    clientUpdatedAt = clientUpdatedAt,
    deleted = false,
    dirty = false,
)

internal fun AnnotationEntity.decodeLocator(): Locator? =
    runCatching { ApiJson.decodeFromString(Locator.serializer(), locator) }.getOrNull()

internal fun AnnotationEntity.toModel(): BookAnnotation? {
    val loc = decodeLocator() ?: return null
    return BookAnnotation(
        key = BookKey(accountId, fileId),
        uuid = uuid,
        type = AnnotationType.fromWire(type),
        locator = loc,
        text = text,
        note = note,
        color = AnnotationColor.fromWire(color),
        createdAt = createdAt,
        clientUpdatedAt = clientUpdatedAt,
        pending = dirty,
    )
}

/** Cuts to [max] UTF-16 units without splitting a surrogate pair (the server counts characters). */
internal fun cut(value: String, max: Int): String {
    if (value.length <= max) return value
    val end = if (Character.isHighSurrogate(value[max - 1])) max - 1 else max
    return value.substring(0, end)
}

/**
 * Reading order like reader-core `compareLocators`: by CFI when both have one, otherwise by comic page
 * or overall progression, then by creation time.
 */
internal val AnnotationOrder: Comparator<BookAnnotation> = Comparator { a, b ->
    val la = a.locator.locations
    val lb = b.locator.locations
    val ca = la?.cfi
    val cb = lb?.cfi
    if (ca != null && cb != null) {
        val c = compareCfi(ca, cb)
        if (c != null && c != 0) return@Comparator c
    }
    val pa = la?.position
    val pb = lb?.position
    if (pa != null && pb != null && pa != pb && ca == null) return@Comparator pa.compareTo(pb)
    val ta = la?.totalProgression
    val tb = lb?.totalProgression
    if (ta != null && tb != null && ta != tb) return@Comparator ta.compareTo(tb)
    a.createdAt.compareTo(b.createdAt)
}

private val CFI_STEP = Regex("""\[[^\]]*]|([/:])(\d+)""")

/**
 * Document order of two EPUB CFIs (`epubcfi(/6/4!/4/2,/1:3,/1:9)`); for ranges the start counts.
 * Compares the numeric steps and character offsets (assertions in brackets are ignored). null when one
 * of them is not a CFI.
 */
internal fun compareCfi(a: String, b: String): Int? {
    val sa = cfiSteps(a) ?: return null
    val sb = cfiSteps(b) ?: return null
    for (i in 0 until minOf(sa.size, sb.size)) {
        val c = sa[i].compareTo(sb[i])
        if (c != 0) return c
    }
    return sa.size.compareTo(sb.size)
}

private fun cfiSteps(cfi: String): List<Long>? {
    if (!cfi.startsWith("epubcfi(") || !cfi.endsWith(")")) return null
    val parts = cfi.substring(8, cfi.length - 1).split(',')
    // range: parent path + start; point: the whole path
    val start = if (parts.size == 3) parts[0] + parts[1] else parts[0]
    val steps = CFI_STEP.findAll(start).mapNotNull { m -> m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() }.toList()
    return steps.takeIf { it.isNotEmpty() }
}
