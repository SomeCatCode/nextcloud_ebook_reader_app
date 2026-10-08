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
    shared = shared,
    owner = owner,
    sharedOut = sharedOut,
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
    shared = shared,
    owner = owner,
    sharedOut = sharedOut,
)

/** Manual shelf memberships and smart shelf queries of the shown accounts, keyed by (accountId, shelfId). */
internal class ShelfLookup(
    val members: Map<Pair<String, Long>, Set<Long>> = emptyMap(),
    val smart: Map<Pair<String, Long>, SmartQueryDto> = emptyMap(),
) {
    companion object {
        fun of(shelves: List<ShelfEntity>, members: List<com.somecatcode.ebookreader.data.db.ShelfBookEntity>): ShelfLookup = ShelfLookup(
            members = members.groupBy({ it.accountId to it.shelfId }, { it.fileId }).mapValues { it.value.toSet() },
            smart = shelves.filter { it.isSmart() }.mapNotNull { s -> s.smartQuery()?.let { (s.accountId to s.id) to it } }.toMap(),
        )
    }
}

/**
 * Evaluates library filters and smart shelf queries against local data, mirroring the server
 * (`LibraryService::filterConditions`): terms are `type:name`; `genre:X/…` and `tag:X/…` (slash-star wildcard) match X and
 * everything below `X/`; `shelf:<id>` is the membership of a manual shelf or the saved query of a smart
 * shelf (whose own `shelf:` terms are ignored, so shelves cannot loop); `missing:<field>` matches books
 * lacking that field. Comparisons are case-insensitive.
 */
internal object SmartQueryEvaluator {

    fun matches(
        query: SmartQueryDto,
        book: BookEntity,
        tags: List<BookTagEntity>,
        shelves: ShelfLookup,
        allowShelf: Boolean = true,
    ): Boolean {
        if (!matchesSearch(query.search, book)) return false
        query.status?.let { if (book.readStatus != it.wire()) return false }
        return matchesTerms(query.include, query.exclude, query.match == "any", book, tags, shelves, allowShelf)
    }

    fun matchesSearch(search: String?, book: BookEntity): Boolean {
        val needle = search?.trim().orEmpty()
        if (needle.isEmpty()) return true
        return listOfNotNull(book.title, book.authors, book.series, book.path.substringAfterLast('/'))
            .any { it.contains(needle, ignoreCase = true) }
    }

    fun matchesTerms(
        include: List<String>,
        exclude: List<String>,
        matchAny: Boolean,
        book: BookEntity,
        tags: List<BookTagEntity>,
        shelves: ShelfLookup,
        allowShelf: Boolean = true,
    ): Boolean {
        // null = the term has no effect (e.g. a shelf term inside a smart shelf)
        val hits = include.mapNotNull { term(it, book, tags, shelves, allowShelf, negate = false) }
        if (hits.isNotEmpty() && !(if (matchAny) hits.any { it } else hits.all { it })) return false
        return exclude.none { term(it, book, tags, shelves, allowShelf, negate = true) == true }
    }

    private fun term(raw: String, book: BookEntity, tags: List<BookTagEntity>, shelves: ShelfLookup, allowShelf: Boolean, negate: Boolean): Boolean? {
        val idx = raw.indexOf(':')
        if (idx <= 0) return null
        val type = raw.substring(0, idx).trim().lowercase()
        val name = raw.substring(idx + 1).trim()
        return when (type) {
            "genre" -> tags.any { it.type == TAG_GENRE && tagMatches(it.name, name) }
            "tag" -> tags.any { it.type == TAG_TAG && tagMatches(it.name, name) }
            "author" -> decodeStrings(book.authors).any { it.trim().equals(name, ignoreCase = true) }
            "series" -> book.series?.trim().equals(name, ignoreCase = true)
            "format" -> book.format.equals(name, ignoreCase = true)
            "missing" -> missing(name, book, tags)
            "shelf" -> if (!allowShelf) null else shelfTerm(name, book, tags, shelves, negate)
            else -> null
        }
    }

    private fun shelfTerm(name: String, book: BookEntity, tags: List<BookTagEntity>, shelves: ShelfLookup, negate: Boolean): Boolean? {
        val id = name.toLongOrNull()?.takeIf { it > 0 } ?: return if (negate) null else false
        val key = book.accountId to id
        shelves.smart[key]?.let { return matches(it, book, tags, shelves, allowShelf = false) }
        val members = shelves.members[key] ?: return if (negate) null else false
        return book.fileId in members
    }

    /** `missing:<field>`; unknown fields have no effect. */
    fun missing(field: String, book: BookEntity, tags: List<BookTagEntity>): Boolean? = when (field.lowercase()) {
        "genre" -> tags.none { it.type == TAG_GENRE }
        "tag" -> tags.none { it.type == TAG_TAG }
        "author" -> decodeStrings(book.authors).none { it.isNotBlank() }
        "series" -> book.series.isNullOrBlank()
        "description" -> book.description.isNullOrBlank()
        "language" -> book.language.isNullOrBlank()
        "cover" -> !book.hasCover
        else -> null
    }

    /** The wildcard "Fantasy/" + "*" matches "Fantasy" and "Fantasy/..." (not "Fantasyx"); a plain term matches the full name. */
    fun tagMatches(tagName: String, term: String): Boolean {
        val tag = tagName.trim().lowercase()
        val t = term.trim()
        if (!t.endsWith("/*")) return tag == t.lowercase()
        val base = normalizeHierarchy(t.dropLast(2))?.lowercase() ?: return false
        return tag == base || tag.startsWith("$base/")
    }

    fun normalizeHierarchy(name: String): String? =
        name.split('/').map { it.trim().replace(WHITESPACE, " ") }.filter { it.isNotEmpty() }.take(5)
            .takeIf { it.isNotEmpty() }?.joinToString("/")

    private val WHITESPACE = Regex("""\s+""")
}
