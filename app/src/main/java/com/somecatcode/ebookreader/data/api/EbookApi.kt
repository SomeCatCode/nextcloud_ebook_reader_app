package com.somecatcode.ebookreader.data.api

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * Typed client for ONE account (base URL + Basic auth with the app password). Created by
 * [ApiClientFactory]. All calls are main-safe suspend functions (they switch to an IO dispatcher
 * themselves) and throw [ApiException] on failure; they never return raw HTTP errors.
 *
 * OCS calls: base `{server}/ocs/v2.php/apps/ebookreader/api/v1`, headers `OCS-APIRequest: true`,
 * `Accept: application/json` and query `format=json`; the body is unwrapped from [OcsEnvelope].
 *
 * Owner of the implementation: W-DATA (`data/api/EbookApiImpl.kt`, tested with MockWebServer).
 */
interface EbookApi {

    // ---- Server probe -----------------------------------------------------------------------------

    /** `GET /ocs/v2.php/cloud/capabilities`. [EbookReaderCapabilities] is null when the app is missing. */
    suspend fun capabilities(): CapabilitiesResponse

    /** `GET /ocs/v2.php/cloud/user` - user id (for WebDAV paths) and display name. */
    suspend fun currentUser(): CloudUser

    /**
     * Whether the installed E-Book Reader app is new enough (>= 0.5.0). The capabilities carry no app
     * version, so the implementation probes `GET /series` (added in 0.5.0): 200 = ok, 404 = too old.
     */
    suspend fun checkCompatibility(): ServerCompatibility

    // ---- Library ----------------------------------------------------------------------------------

    /** `GET /books` */
    suspend fun books(query: BookQuery = BookQuery()): BookListDto

    /** `GET /books/{fileId}` */
    suspend fun book(fileId: Long): BookDto

    /** `GET /sync?cursor=` - one page; callers loop while [SyncDto.hasMore]. */
    suspend fun sync(cursor: String): SyncDto

    /** `GET /facets` */
    suspend fun facets(): FacetsDto

    /** `GET /series` (same filter parameters as books, only `sort=name|added` is meaningful). */
    suspend fun series(query: BookQuery = BookQuery()): List<SeriesDto>

    /** `GET /shelves` */
    suspend fun shelves(): List<ShelfDto>

    /** `POST /shelves` - manual shelf ([query] null) or smart shelf. */
    suspend fun createShelf(name: String, query: SmartQueryDto?): ShelfDto

    /** `PATCH /shelves/{id}` - only the given fields change. */
    suspend fun updateShelf(id: Long, name: String? = null, query: SmartQueryDto? = null, sortOrder: Int? = null): ShelfDto

    /** `DELETE /shelves/{id}` - the books stay in the library. */
    suspend fun deleteShelf(id: Long)

    /** `POST /shelves/{id}/books` (manual shelves, max. 500 ids). */
    suspend fun addToShelf(id: Long, fileIds: List<Long>)

    /** `DELETE /shelves/{id}/books` with body `{fileIds}`. */
    suspend fun removeFromShelf(id: Long, fileIds: List<Long>)

    /** `GET /progress/recent?limit=` */
    suspend fun recentBooks(limit: Int = 10): List<BookDto>

    // ---- Progress ---------------------------------------------------------------------------------

    /** `GET /progress/{fileId}`; `null` if the book was never opened (server answers `data: null`). */
    suspend fun progress(fileId: Long): ProgressDto?

    /**
     * `PUT /progress/{fileId}`. HTTP 409 is NOT an exception: it is returned as
     * [ProgressPutResult.Conflict] carrying the server's current progress.
     */
    suspend fun putProgress(fileId: Long, body: ProgressPutRequest): ProgressPutResult

    /** `POST /progress/batch` (max 100 items per call - the implementation chunks). */
    suspend fun putProgressBatch(items: List<ProgressBatchItem>): List<ProgressBatchResultItem>

    // ---- Editing ----------------------------------------------------------------------------------

    /** `PATCH /books/{fileId}/app-data` - rating and read status (stored in the database only). */
    suspend fun patchAppData(fileId: Long, patch: AppDataPatch): BookDto

    /** `PATCH /books/{fileId}/metadata` - title, authors, series, genres, tags, ... */
    suspend fun patchMetadata(fileId: Long, patch: MetadataPatch): SaveResultDto

    // ---- Non-OCS content endpoints (`{server}/index.php/apps/ebookreader/...`) --------------------

    /** `GET /comic/{fileId}/pages` (CBZ/CBT/CBR/CB7). */
    suspend fun comicPages(fileId: Long): ComicPagesDto

    /** `GET /archive/{fileId}/entries` (EPUB/CBZ/FBZ). */
    suspend fun archiveEntries(fileId: Long): ArchiveEntriesDto

    // ---- URL builders (no I/O) --------------------------------------------------------------------

    /** `GET /cover/{fileId}?size=small|large` (needs auth: load through [http]). */
    fun coverUrl(fileId: Long, size: CoverSize = CoverSize.SMALL): HttpUrl

    /** `GET /comic/{fileId}/page/{index}?w=<px>` */
    fun comicPageUrl(fileId: Long, index: Int, width: Int = 0): HttpUrl

    /** `GET /item/{fileId}?id=<entry>` - raw zip entry of an EPUB/CBZ/FBZ. */
    fun itemUrl(fileId: Long, entry: String): HttpUrl

    /** `GET /remote.php/dav/files/{userId}/{path}` - WebDAV download URL of the book file. */
    fun davFileUrl(userId: String, path: String): HttpUrl

    /** Authenticated raw HTTP access for covers, WebDAV and the reader request proxy. */
    val http: AuthenticatedHttp

    // ---- Account -----------------------------------------------------------------------------------

    /** `DELETE /ocs/v2.php/core/apppassword` - revokes the app password used by this client (logout). */
    suspend fun revokeAppPassword()
}

/** Raw authenticated HTTP: adds `Authorization: Basic ...` and `OCS-APIRequest: true`; follows no cross-host redirects. */
interface AuthenticatedHttp {
    /** Blocking call, run it on an IO dispatcher. The caller must close the [Response]. */
    fun execute(request: Request): Response

    /** Returns [request] with the auth headers added, for use with Coil or custom clients. */
    fun authorize(request: Request): Request
}

enum class CoverSize(val wire: String) { SMALL("small"), LARGE("large") }

/** Result of a single progress PUT. */
sealed interface ProgressPutResult {
    data class Stored(val progress: ProgressDto) : ProgressPutResult
    /** Server has a newer position (HTTP 409). */
    data class Conflict(val current: ProgressDto) : ProgressPutResult
}

/** Outcome of [EbookApi.checkCompatibility]. */
sealed interface ServerCompatibility {
    data class Ok(val capabilities: EbookReaderCapabilities, val serverVersion: String) : ServerCompatibility
    /** E-Book Reader app not installed or disabled. */
    data object AppMissing : ServerCompatibility
    /** App installed but older than 0.5.0 (no `/series`, `/shelves`, archive entries). */
    data object AppTooOld : ServerCompatibility
}

/** Parameters of `GET /books` (and `/series`). Mirrors the server `BookQuery`. */
data class BookQuery(
    val search: String? = null,
    val include: List<FilterTerm> = emptyList(),
    val exclude: List<FilterTerm> = emptyList(),
    val match: MatchMode = MatchMode.ALL,
    val status: ReadStatus? = null,
    val sort: SortKey = SortKey.TITLE,
    val descending: Boolean = false,
    /** true = only books with a series, false = only books without, null = all. */
    val inSeries: Boolean? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

/** Sent as `include[]=type:name` / `exclude[]=type:name`; a trailing slash-star wildcard (Fantasy/ + star) matches a hierarchy. */
data class FilterTerm(val type: FilterType, val name: String)

enum class FilterType(val wire: String) { GENRE("genre"), TAG("tag"), AUTHOR("author"), SERIES("series"), FORMAT("format"), SHELF("shelf") }

enum class MatchMode(val wire: String) { ALL("all"), ANY("any") }

enum class SortKey(val wire: String) {
    TITLE("title"), AUTHOR("author"), SERIES("series"), RATING("rating"), ADDED("added"), READ("read"), SHELF("shelf"),
}

/** Login Flow v2 against `{server}/index.php/login/v2` (no account yet, no auth). */
interface LoginFlowClient {
    /** Normalises the user input (adds `https://`, strips trailing `/index.php` etc.) and starts the flow. Only HTTPS is accepted. */
    suspend fun start(serverInput: String): LoginFlowStart

    /**
     * Polls [LoginFlowPoll.endpoint] once. Returns null while the user has not finished (HTTP 404);
     * the caller polls every ~2 s until success, cancellation or a 20 minute timeout.
     */
    suspend fun pollOnce(poll: LoginFlowPoll): LoginFlowResult?
}

/** Creates [EbookApi] instances bound to an account. Implementations cache one client per account id. */
interface ApiClientFactory {
    /** Credentials are read from the AccountStore; throws [ApiException.Unauthorized] if they are missing. */
    suspend fun forAccount(accountId: String): EbookApi

    /** For the add-account flow before the account is persisted. */
    fun forCredentials(serverUrl: String, loginName: String, appPassword: String): EbookApi
}
