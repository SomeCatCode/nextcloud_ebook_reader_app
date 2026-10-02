package com.somecatcode.ebookreader.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Shared JSON configuration for all server communication and for JSON columns in the database.
 * Unknown keys are ignored (the server may add fields), nulls are written explicitly only when
 * needed (`explicitNulls = false` keeps PATCH bodies minimal; use [PatchField] for "set to null").
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
    isLenient = false
}

// ---- OCS envelope ---------------------------------------------------------------------------------

/** `{"ocs": {"meta": {...}, "data": ...}}` - every `/ocs/v2.php/...` response. */
@Serializable
data class OcsEnvelope<T>(val ocs: OcsBody<T>)

@Serializable
data class OcsBody<T>(val meta: OcsMeta, val data: T)

@Serializable
data class OcsMeta(
    val status: String,
    val statuscode: Int,
    val message: String? = null,
)

// ---- Login Flow v2 --------------------------------------------------------------------------------

/** Response of `POST {server}/index.php/login/v2`. Open [login] in the browser, then poll. */
@Serializable
data class LoginFlowStart(
    val poll: LoginFlowPoll,
    /** URL the user opens in a browser (Custom Tab) to grant access. */
    val login: String,
)

@Serializable
data class LoginFlowPoll(
    val token: String,
    /** Full URL for `POST` with form field `token=<token>`. HTTP 404 until the user finished. */
    val endpoint: String,
)

/** Response of the poll endpoint once the user granted access. */
@Serializable
data class LoginFlowResult(
    /** Normalised server base URL, e.g. `https://cloud.example.org` (may contain a sub-path). */
    val server: String,
    val loginName: String,
    val appPassword: String,
)

// ---- Capabilities and user -------------------------------------------------------------------------

/** `data` of `GET /ocs/v2.php/cloud/capabilities`. Only the parts the app needs. */
@Serializable
data class CapabilitiesResponse(
    val version: ServerVersion? = null,
    val capabilities: CapabilitiesBlock = CapabilitiesBlock(),
)

@Serializable
data class ServerVersion(
    val major: Int = 0,
    val minor: Int = 0,
    val micro: Int = 0,
    val string: String = "",
)

@Serializable
data class CapabilitiesBlock(
    /** Absent when the E-Book Reader app is not installed/enabled. */
    val ebookreader: EbookReaderCapabilities? = null,
)

/** Server `lib/Capabilities.php`: `{apiVersion, apiStable, formats, editor}`. */
@Serializable
data class EbookReaderCapabilities(
    val apiVersion: Int = 0,
    val apiStable: Boolean = false,
    val formats: List<String> = emptyList(),
    val editor: Boolean = false,
)

/** `data` of `GET /ocs/v2.php/cloud/user`. */
@Serializable
data class CloudUser(
    val id: String,
    @SerialName("display-name") val displayName: String? = null,
    val displayname: String? = null,
)

// ---- Books ----------------------------------------------------------------------------------------

/** Lower-case wire values: `unread|reading|finished`. Unknown values fall back to [UNREAD]. */
@Serializable
enum class ReadStatus {
    @SerialName("unread") UNREAD,
    @SerialName("reading") READING,
    @SerialName("finished") FINISHED,
}

/** Readium-like locator, identical to the server and to reader-core (`ReaderLocator`). */
@Serializable
data class Locator(
    val href: String,
    val type: String? = null,
    val title: String? = null,
    val locations: Locations? = null,
)

@Serializable
data class Locations(
    /** Position inside the current resource, 0..1. */
    val progression: Double? = null,
    /** Position inside the whole book, 0..1. */
    val totalProgression: Double? = null,
    /** 1-based page number (comics). */
    val position: Int? = null,
    /** Full EPUB CFI (extension of this project). */
    val cfi: String? = null,
)

/** Server `EbookReaderProgress`. All timestamps are milliseconds since epoch. */
@Serializable
data class ProgressDto(
    val fileId: Long,
    val locator: Locator,
    /** Overall progress 0..1. */
    val percentage: Double,
    val device: String? = null,
    val clientUpdatedAt: Long,
    val updatedAt: Long,
)

/** Server `EbookReaderBook`. Timestamps in milliseconds since epoch. */
@Serializable
data class BookDto(
    val fileId: Long,
    /** `epub|mobi|azw3|fb2|fbz|cbz|cbr|cb7|cbt` */
    val format: String,
    /** User-relative path, e.g. `/Books/x.epub`; WebDAV path below `/remote.php/dav/files/{userId}`. */
    val path: String,
    val size: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Double? = null,
    /** Sanitised HTML. */
    val description: String? = null,
    val language: String? = null,
    val publisher: String? = null,
    val isbn: String? = null,
    val publishedAt: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: Int? = null,
    val readStatus: ReadStatus = ReadStatus.UNREAD,
    val hasCover: Boolean = false,
    val coverEtag: String? = null,
    val mtime: Long = 0,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val editable: Boolean = false,
    /** false for view-only shares without download permission: the book cannot be read in the app. */
    val downloadable: Boolean = true,
    /** Metadata fields changed in the app only (title, authors, series, ...). */
    val overrides: List<String> = emptyList(),
    val hasSidecar: Boolean = false,
    val progress: ProgressDto? = null,
)

@Serializable
data class BookListDto(val books: List<BookDto>, val total: Int)

/** `GET /sync`: each list holds at most 500 entries; while [hasMore] call again with [cursor]. */
@Serializable
data class SyncDto(
    val books: List<BookDto> = emptyList(),
    /** File ids deleted on the server. */
    val deleted: List<Long> = emptyList(),
    val progress: List<ProgressDto> = emptyList(),
    /** Opaque; store verbatim as `lastSyncCursor`. Empty string = full sync. */
    val cursor: String,
    val hasMore: Boolean = false,
)

@Serializable
data class FacetEntryDto(val name: String, val count: Int)

@Serializable
data class FacetsDto(
    val genres: List<FacetEntryDto> = emptyList(),
    val tags: List<FacetEntryDto> = emptyList(),
    val authors: List<FacetEntryDto> = emptyList(),
    val series: List<FacetEntryDto> = emptyList(),
    val formats: List<FacetEntryDto> = emptyList(),
)

// ---- Shelves and series ---------------------------------------------------------------------------

/** Saved filter of a smart shelf; terms are `type:name` strings (`genre:Fantasy`, `tag:x/` plus a star wildcard, `author:`, `series:`, `format:`, `shelf:`). */
@Serializable
data class SmartQueryDto(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    /** `all|any` */
    val match: String = "all",
    val search: String = "",
    val status: ReadStatus? = null,
    val sort: String = "title",
    /** `asc|desc` */
    val order: String = "asc",
)

@Serializable
data class ShelfDto(
    val id: Long,
    val name: String,
    /** `manual|smart` */
    val type: String,
    val query: SmartQueryDto? = null,
    val count: Int = 0,
    /** At most 4 */
    val coverFileIds: List<Long> = emptyList(),
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class ShelvesDto(val shelves: List<ShelfDto>)

@Serializable
data class SeriesDto(
    val name: String,
    val count: Int,
    val readCount: Int = 0,
    /** At most 3, lowest volume first */
    val coverFileIds: List<Long> = emptyList(),
    val firstFileId: Long,
    val lastAddedAt: Long = 0,
)

@Serializable
data class SeriesListDto(val series: List<SeriesDto>)

// ---- Progress -------------------------------------------------------------------------------------

/** Body of `PUT /progress/{fileId}`. */
@Serializable
data class ProgressPutRequest(
    val locator: Locator,
    val percentage: Double,
    val device: String? = null,
    val clientUpdatedAt: Long,
)

/** HTTP 409 body (`data`) of `PUT /progress/{fileId}`: the server holds a newer position. */
@Serializable
data class ProgressConflictDto(val current: ProgressDto)

@Serializable
data class ProgressBatchItem(
    val fileId: Long,
    val locator: Locator,
    val percentage: Double,
    val device: String? = null,
    val clientUpdatedAt: Long,
)

/** Body of `POST /progress/batch` (max 100 items). */
@Serializable
data class ProgressBatchRequest(val items: List<ProgressBatchItem>)

@Serializable
data class ProgressBatchResultItem(
    val fileId: Long,
    /** `ok|conflict|error` */
    val status: String,
    val progress: ProgressDto? = null,
    val error: String? = null,
)

@Serializable
data class ProgressBatchResult(val results: List<ProgressBatchResultItem>)

@Serializable
data class RecentBooksDto(val books: List<BookDto>)

// ---- Editing --------------------------------------------------------------------------------------

/**
 * Body of `PATCH /books/{id}/app-data`. Absent fields stay unchanged; to clear the rating set
 * [setRating] = true and [rating] = null (sent as explicit JSON null). Rating range 0..5.
 */
data class AppDataPatch(
    val setRating: Boolean = false,
    val rating: Int? = null,
    val readStatus: ReadStatus? = null,
)

/**
 * Body of `PATCH /books/{id}/metadata`; the client builds a JSON object from [fields] (not serialised
 * as a wrapper). Only the given keys are sent; a `JsonNull` value clears a field. Keys: title,
 * authors (array), series, seriesIndex (number), description, language, publisher, isbn,
 * publishedAt, genres (array), tags (array).
 */
data class MetadataPatch(val fields: Map<String, JsonElement>)

/** `data` of `PATCH /books/{id}/metadata` (`SaveResult`). */
@Serializable
data class SaveResultDto(
    val book: BookDto,
    val warnings: List<String> = emptyList(),
    /** The server writes the file in a background job shortly afterwards. */
    val writeQueued: Boolean = false,
)

// ---- Non-OCS endpoints ----------------------------------------------------------------------------

/** `GET /apps/ebookreader/comic/{id}/pages` */
@Serializable
data class ComicPagesDto(val etag: String, val pages: List<ArchiveEntry>)

/** `GET /apps/ebookreader/archive/{id}/entries` (EPUB, CBZ, FBZ) */
@Serializable
data class ArchiveEntriesDto(val etag: String, val entries: List<ArchiveEntry>)

@Serializable
data class ArchiveEntry(val name: String, val size: Long = 0)
