package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.BookDto
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.api.ProgressDto
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.api.ShelfDto
import com.somecatcode.ebookreader.data.api.SmartQueryDto
import com.somecatcode.ebookreader.data.api.wire
import com.somecatcode.ebookreader.data.db.BookEntity
import com.somecatcode.ebookreader.data.db.BookTagEntity
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.db.ProgressEntity
import com.somecatcode.ebookreader.data.db.ShelfEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/* Conversions between wire DTOs, Room entities and UI models. */

internal const val TAG_GENRE = "genre"
internal const val TAG_TAG = "tag"

private val stringList = ListSerializer(String.serializer())
private val longList = ListSerializer(Long.serializer())

internal fun encodeStrings(list: List<String>): String = ApiJson.encodeToString(stringList, list)

internal fun decodeStrings(json: String?): List<String> =
    if (json.isNullOrBlank()) emptyList() else runCatching { ApiJson.decodeFromString(stringList, json) }.getOrDefault(emptyList())

internal fun encodeLongs(list: List<Long>): String = ApiJson.encodeToString(longList, list)

internal fun decodeLongs(json: String?): List<Long> =
    if (json.isNullOrBlank()) emptyList() else runCatching { ApiJson.decodeFromString(longList, json) }.getOrDefault(emptyList())

internal fun parseReadStatus(value: String?): ReadStatus = when (value) {
    "reading" -> ReadStatus.READING
    "finished" -> ReadStatus.FINISHED
    else -> ReadStatus.UNREAD
}

internal fun BookDto.toEntity(accountId: String, fileEtag: String?): BookEntity = BookEntity(
    accountId = accountId,
    fileId = fileId,
    format = format,
    path = path,
    size = size,
    title = title,
    authors = encodeStrings(authors),
    series = series?.takeIf { it.isNotBlank() },
    seriesIndex = seriesIndex,
    description = description,
    language = language,
    publisher = publisher,
    isbn = isbn,
    publishedAt = publishedAt,
    rating = rating,
    readStatus = readStatus.wire(),
    hasCover = hasCover,
    coverEtag = coverEtag,
    fileEtag = fileEtag,
    mtime = mtime,
    addedAt = addedAt,
    updatedAt = updatedAt,
    editable = editable,
    downloadable = downloadable,
    overrides = encodeStrings(overrides),
    hasSidecar = hasSidecar,
    deleted = false,
)

internal fun BookDto.tagEntities(accountId: String): List<BookTagEntity> =
    (genres.distinct().map { BookTagEntity(accountId, fileId, TAG_GENRE, it) } +
        tags.distinct().map { BookTagEntity(accountId, fileId, TAG_TAG, it) })

internal fun ProgressDto.toEntity(accountId: String): ProgressEntity = ProgressEntity(
    accountId = accountId,
    fileId = fileId,
    locator = ApiJson.encodeToString(Locator.serializer(), locator),
    percentage = percentage,
    device = device,
    clientUpdatedAt = clientUpdatedAt,
    updatedAt = updatedAt,
    dirty = false,
)

internal fun ProgressEntity.decodeLocator(): Locator? =
    runCatching { ApiJson.decodeFromString(Locator.serializer(), locator) }.getOrNull()

internal fun ProgressEntity.toStored(): StoredProgress? = decodeLocator()?.let {
    StoredProgress(BookKey(accountId, fileId), it, percentage, device, clientUpdatedAt, dirty)
}

internal fun ShelfDto.toEntity(accountId: String): ShelfEntity = ShelfEntity(
    accountId = accountId,
    id = id,
    name = name,
    type = type,
    query = query?.let { ApiJson.encodeToString(SmartQueryDto.serializer(), it) },
    count = count,
    coverFileIds = encodeLongs(coverFileIds),
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ShelfEntity.isSmart(): Boolean = type == "smart"

internal fun ShelfEntity.smartQuery(): SmartQueryDto? =
    query?.let { runCatching { ApiJson.decodeFromString(SmartQueryDto.serializer(), it) }.getOrNull() }

internal fun DownloadEntity.offlineState() = OfflineState(
    state = runCatching { DownloadState.valueOf(state) }.getOrNull(),
    bytes = bytes,
    total = total,
    pinnedBy = runCatching { PinnedBy.valueOf(pinnedBy) }.getOrNull(),
)

internal fun BookEntity.fallbackTitle(): String =
    title?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').substringBeforeLast('.')

internal fun BookEntity.toLibraryBook(
    tags: List<BookTagEntity>,
    progress: ProgressEntity?,
    download: DownloadEntity?,
    hasPendingEdit: Boolean,
): LibraryBook = LibraryBook(
    key = BookKey(accountId, fileId),
    format = format,
    path = path,
    size = size,
    title = fallbackTitle(),
    authors = decodeStrings(authors),
    series = series,
    seriesIndex = seriesIndex,
    descriptionHtml = description,
    language = language,
    publisher = publisher,
    isbn = isbn,
    publishedAt = publishedAt,
    genres = tags.filter { it.type == TAG_GENRE }.map { it.name }.sortedBy { it.lowercase() },
    tags = tags.filter { it.type == TAG_TAG }.map { it.name }.sortedBy { it.lowercase() },
    rating = rating,
    readStatus = parseReadStatus(readStatus),
    hasCover = hasCover,
    coverEtag = coverEtag,
    addedAt = addedAt,
    editable = editable,
    downloadable = downloadable,
    percentage = progress?.percentage,
    offline = download?.offlineState() ?: OfflineState(null),
    hasPendingEdit = hasPendingEdit,
)

/**
 * Evaluates the stored query of a smart shelf (or any include/exclude filter) against local data.
 * Terms are `type:name`; a wildcard term (name ending in slash-star) also matches everything below that prefix.
 */
internal object SmartQueryEvaluator {

    fun matches(
        query: SmartQueryDto,
        book: BookEntity,
        tags: List<BookTagEntity>,
        shelfMembers: Map<Long, Set<Long>>,
    ): Boolean {
        if (query.search.isNotBlank()) {
            val needle = query.search.trim()
            val hay = listOfNotNull(book.title, book.authors, book.series)
            if (hay.none { it.contains(needle, ignoreCase = true) }) return false
        }
        query.status?.let { if (book.readStatus != it.wire()) return false }
        val includeHits = query.include.map { term(it, book, tags, shelfMembers) }
        if (includeHits.isNotEmpty()) {
            val ok = if (query.match == "any") includeHits.any { it } else includeHits.all { it }
            if (!ok) return false
        }
        if (query.exclude.any { term(it, book, tags, shelfMembers) }) return false
        return true
    }

    private fun term(raw: String, book: BookEntity, tags: List<BookTagEntity>, shelfMembers: Map<Long, Set<Long>>): Boolean {
        val idx = raw.indexOf(':')
        if (idx <= 0) return false
        val type = raw.substring(0, idx)
        val name = raw.substring(idx + 1)
        return when (type) {
            "genre" -> tagMatch(tags, TAG_GENRE, name)
            "tag" -> tagMatch(tags, TAG_TAG, name)
            "author" -> decodeStrings(book.authors).any { it.equals(name, ignoreCase = true) }
            "series" -> book.series.equals(name, ignoreCase = true)
            "format" -> book.format.equals(name, ignoreCase = true)
            "shelf" -> name.toLongOrNull()?.let { shelfMembers[it]?.contains(book.fileId) } == true
            else -> false
        }
    }

    private fun tagMatch(tags: List<BookTagEntity>, type: String, name: String): Boolean {
        val wildcard = name.endsWith("/*")
        val prefix = name.removeSuffix("*")
        return tags.any {
            it.type == type &&
                if (wildcard) it.name.startsWith(prefix, ignoreCase = true) || it.name.equals(prefix.trimEnd('/'), ignoreCase = true)
                else it.name.equals(name, ignoreCase = true)
        }
    }
}
