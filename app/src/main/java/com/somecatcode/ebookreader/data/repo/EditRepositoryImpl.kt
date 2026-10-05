package com.somecatcode.ebookreader.data.repo

import androidx.room.withTransaction
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.MetadataPatch
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.api.wire
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.db.BookTagEntity
import com.somecatcode.ebookreader.data.db.PendingEditEntity
import com.somecatcode.ebookreader.data.sync.LocalChangesScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Optimistic editing: Room is updated at once, the change is merged into one `pending_edit` row per
 * (book, kind) and uploaded by [flushPending]. Rejected edits (400/403/404) are discarded, the book
 * is reloaded from the server (best effort) and the failure is reported through [failures].
 */
class EditRepositoryImpl(
    private val db: AppDatabase,
    private val apiFactory: ApiClientFactory,
    private val scheduler: LocalChangesScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) : EditRepository {

    private val flushLock = Mutex()
    private val failureFlow = MutableSharedFlow<EditFailure>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val failures: Flow<EditFailure> = failureFlow.asSharedFlow()

    override val pendingCount: Flow<Int> get() = db.pendingEditDao().observeCount()

    override suspend fun editMetadata(key: BookKey, patch: MetadataPatch) {
        if (patch.fields.isEmpty()) return
        db.withTransaction {
            val book = db.bookDao().get(key.accountId, key.fileId) ?: return@withTransaction
            db.bookDao().upsertAll(listOf(applyMetadata(book, patch.fields)))
            if ("genres" in patch.fields || "tags" in patch.fields) {
                val tags = db.bookTagDao().forBook(key.accountId, key.fileId).toMutableList()
                patch.fields["genres"]?.let { replaceTags(tags, key, TAG_GENRE, it) }
                patch.fields["tags"]?.let { replaceTags(tags, key, TAG_TAG, it) }
                db.bookTagDao().replaceForBook(key.accountId, key.fileId, tags)
            }
            mergePending(key, KIND_METADATA, patch.fields)
        }
        scheduler.schedule(key.accountId)
    }

    override suspend fun editAppData(key: BookKey, patch: AppDataPatch) {
        val fields = LinkedHashMap<String, JsonElement>()
        if (patch.setRating) fields["rating"] = patch.rating?.let { JsonPrimitive(it.coerceIn(0, 5)) } ?: JsonNull
        patch.readStatus?.let { fields["readStatus"] = JsonPrimitive(it.wire()) }
        if (fields.isEmpty()) return
        db.withTransaction {
            val book = db.bookDao().get(key.accountId, key.fileId) ?: return@withTransaction
            val rating = if (patch.setRating) patch.rating?.coerceIn(0, 5) else book.rating
            val status = patch.readStatus?.wire() ?: book.readStatus
            db.bookDao().updateAppData(key.accountId, key.fileId, rating, status)
            // Status and progress are coupled (like the server): finished = 100 %, unread = back to the start.
            when (patch.readStatus) {
                ReadStatus.FINISHED -> db.progressDao().get(key.accountId, key.fileId)?.let { p ->
                    db.progressDao().upsert(p.copy(percentage = 1.0, dirty = false))
                }
                ReadStatus.UNREAD -> db.progressDao().deleteForBooks(key.accountId, listOf(key.fileId))
                else -> Unit
            }
            mergePending(key, KIND_APP_DATA, fields)
        }
        scheduler.schedule(key.accountId)
    }

    override suspend fun flushPending(accountId: String) = flushLock.withLock {
        val edits = db.pendingEditDao().forAccount(accountId)
        if (edits.isEmpty()) return@withLock
        val api = try {
            apiFactory.forAccount(accountId)
        } catch (e: ApiException) {
            return@withLock
        }
        for (edit in edits) {
            val fields = parseFields(edit.patch)
            try {
                when (edit.kind) {
                    KIND_METADATA -> api.patchMetadata(edit.fileId, MetadataPatch(fields))
                    else -> api.patchAppData(edit.fileId, toAppDataPatch(fields))
                }
                completed(edit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                when (e) {
                    is ApiException.BadRequest, is ApiException.Forbidden, is ApiException.NotFound -> {
                        discard(edit, e, api)
                    }
                    is ApiException.Unauthorized -> return@withLock
                    else -> { // Network, Server, RateLimited: keep, retry later, stop for now
                        db.pendingEditDao().upsert(edit.copy(attempts = edit.attempts + 1, lastError = e.message))
                        return@withLock
                    }
                }
            }
        }
    }

    // ---- internals ------------------------------------------------------------------------------------

    private suspend fun completed(edit: PendingEditEntity) {
        db.withTransaction {
            // Only drop the row if it was not extended while the upload was running.
            val cur = db.pendingEditDao().get(edit.id)
            if (cur != null && cur.patch == edit.patch) db.pendingEditDao().delete(edit.id)
        }
    }

    private suspend fun discard(edit: PendingEditEntity, cause: ApiException, api: com.somecatcode.ebookreader.data.api.EbookApi) {
        db.pendingEditDao().delete(edit.id)
        val key = BookKey(edit.accountId, edit.fileId)
        failureFlow.tryEmit(EditFailure(key, cause.message ?: "rejected"))
        // Roll the optimistic change back by taking the server's version of the book.
        try {
            val dto = api.book(edit.fileId)
            db.withTransaction {
                val existing = db.bookDao().get(edit.accountId, edit.fileId)
                db.bookDao().upsertAll(listOf(dto.toEntity(edit.accountId, existing?.fileEtag)))
                db.bookTagDao().replaceForBook(edit.accountId, edit.fileId, dto.tagEntities(edit.accountId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the next sync corrects it
        }
    }

    private suspend fun mergePending(key: BookKey, kind: String, fields: Map<String, JsonElement>) {
        val dao = db.pendingEditDao()
        val existing = dao.find(key.accountId, key.fileId, kind)
        val merged = LinkedHashMap<String, JsonElement>()
        if (existing != null) merged.putAll(parseFields(existing.patch))
        merged.putAll(fields)
        dao.upsert(
            PendingEditEntity(
                id = existing?.id ?: 0,
                accountId = key.accountId,
                fileId = key.fileId,
                kind = kind,
                patch = ApiJson.encodeToString(JsonObject.serializer(), JsonObject(merged)),
                createdAt = existing?.createdAt ?: clock(),
                attempts = 0,
                lastError = null,
            ),
        )
    }

    private fun applyMetadata(book: BookEntity, f: Map<String, JsonElement>): BookEntity {
        fun str(k: String, old: String?): String? = if (k in f) (f[k] as? JsonPrimitive)?.contentOrNull else old
        return book.copy(
            title = str("title", book.title),
            authors = f["authors"]?.let { encodeStrings(stringsOf(it)) } ?: book.authors,
            series = str("series", book.series)?.takeIf { it.isNotBlank() },
            seriesIndex = if ("seriesIndex" in f) (f["seriesIndex"] as? JsonPrimitive)?.doubleOrNull else book.seriesIndex,
            description = str("description", book.description),
            language = str("language", book.language),
            publisher = str("publisher", book.publisher),
            isbn = str("isbn", book.isbn),
            publishedAt = str("publishedAt", book.publishedAt),
        )
    }

    private fun replaceTags(tags: MutableList<BookTagEntity>, key: BookKey, type: String, value: JsonElement) {
        tags.removeAll { it.type == type }
        stringsOf(value).distinct().forEach { tags += BookTagEntity(key.accountId, key.fileId, type, it) }
    }

    private fun stringsOf(e: JsonElement): List<String> =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun parseFields(json: String): Map<String, JsonElement> =
        runCatching { ApiJson.parseToJsonElement(json).jsonObject.toMap() }.getOrDefault(emptyMap())

    private fun toAppDataPatch(f: Map<String, JsonElement>): AppDataPatch {
        val hasRating = "rating" in f
        val ratingEl = f["rating"] as? JsonPrimitive
        return AppDataPatch(
            setRating = hasRating,
            rating = if (ratingEl == null || ratingEl is JsonNull) null else ratingEl.intOrNull,
            readStatus = (f["readStatus"] as? JsonPrimitive)?.contentOrNull?.let { parseReadStatus(it) },
        )
    }

    companion object {
        const val KIND_METADATA = "metadata"
        const val KIND_APP_DATA = "appData"
    }
}
