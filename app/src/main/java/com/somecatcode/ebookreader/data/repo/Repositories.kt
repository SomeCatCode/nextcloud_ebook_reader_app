package com.somecatcode.ebookreader.data.repo

import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.api.MetadataPatch
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.db.PinnedBy
import kotlinx.coroutines.flow.Flow

/*
 * Repository interfaces: the ONLY data access used by ViewModels. Implementations (W-DATA) combine
 * Room, the API client and the sync/download machinery. All Flows are cold, emit on every database
 * change and never throw for network problems (those surface through SyncState/DownloadState).
 * `accountIds` = the accounts shown (one for a single library, all for the merged view).
 */

// ---- UI models ------------------------------------------------------------------------------------

/** Identity of a book across accounts. */
data class BookKey(val accountId: String, val fileId: Long)

/** A book as the UI needs it: decoded columns + tags + local progress + offline state. */
data class LibraryBook(
    val key: BookKey,
    val format: String,
    val path: String,
    val size: Long,
    val title: String,
    val authors: List<String>,
    val series: String?,
    val seriesIndex: Double?,
    val descriptionHtml: String?,
    val language: String?,
    val publisher: String?,
    val isbn: String?,
    val publishedAt: String?,
    val genres: List<String>,
    val tags: List<String>,
    val rating: Int?,
    val readStatus: ReadStatus,
    val hasCover: Boolean,
    val coverEtag: String?,
    val addedAt: Long,
    val editable: Boolean,
    /** false = view-only share: no download, no reading in the app. */
    val downloadable: Boolean,
    /** Reading position 0..1 (local, possibly not yet uploaded). */
    val percentage: Double?,
    val offline: OfflineState,
    /** Offline edit waiting for upload. */
    val hasPendingEdit: Boolean,
    /** Incoming: the file belongs to another user ([owner]). */
    val shared: Boolean = false,
    val owner: String? = null,
    /** Outgoing: own book shared with other users. */
    val sharedOut: Boolean = false,
)

/** Offline state of a book file. */
data class OfflineState(val state: DownloadState?, val bytes: Long = 0, val total: Long = 0, val pinnedBy: PinnedBy? = null) {
    val isAvailableOffline: Boolean get() = state == DownloadState.DONE
}

data class ShelfInfo(
    val key: ShelfKey,
    val name: String,
    val smart: Boolean,
    val count: Int,
    val coverFileIds: List<Long>,
    /** Saved filter of a smart shelf, null for manual shelves. */
    val query: com.somecatcode.ebookreader.data.api.SmartQueryDto? = null,
)

data class ShelfKey(val accountId: String, val shelfId: Long)

data class SeriesInfo(
    val accountId: String,
    val name: String,
    val count: Int,
    val readCount: Int,
    val coverFileIds: List<Long>,
    /** At least one volume is shared with other users. */
    val sharedOut: Boolean = false,
    /** At least one volume comes from another user. */
    val shared: Boolean = false,
)

data class FacetCount(val name: String, val count: Int)

/** Everything the filter UI offers, counted over the shown (non-deleted) books. */
data class LibraryFacets(
    val genres: List<FacetCount> = emptyList(),
    val tags: List<FacetCount> = emptyList(),
    val authors: List<FacetCount> = emptyList(),
    val series: List<FacetCount> = emptyList(),
    val formats: List<FacetCount> = emptyList(),
    /** Books lacking a field, keyed by [MissingFields] (only fields with at least one book). */
    val missing: Map<String, Int> = emptyMap(),
)

/** Fields a `missing:<field>` term can ask for (same as the server). */
val MissingFields = listOf("genre", "tag", "author", "series", "description", "cover", "language")

/**
 * Library filter, same semantics as the web app and the server `GET /books`: [include] and [exclude] hold
 * `type:name` terms (`genre`, `tag`, `author`, `series`, `format`, `shelf:<id>`, `missing:<field>`; a genre or tag term ending in slash-star
 * also matches everything below that prefix). Includes must all match ([matchAny] = at least one), a book
 * with any excluded term is dropped. [hideFinished] leaves out finished books unless a [status] is set.
 */
data class LibraryFilter(
    val search: String? = null,
    val status: ReadStatus? = null,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val matchAny: Boolean = false,
    val hideFinished: Boolean = false,
    val shelf: ShelfKey? = null,
    val series: String? = null,
    val onlyOffline: Boolean = false,
    /** Only incoming / outgoing / any shared books; null = no restriction. */
    val shared: SharedFilter? = null,
    /** Only books directly in this folder (path relative to the home folder, leading slash, "" = top level). */
    val folder: String? = null,
    /** With [folder]: also books in subfolders. */
    val folderRecursive: Boolean = false,
    val sort: LibrarySort = LibrarySort.TITLE,
    val descending: Boolean = false,
)

/** Same values as the server `shared` parameter: `incoming|outgoing|any`. */
enum class SharedFilter { ANY, INCOMING, OUTGOING }

/** [SHELF] = position inside a manual shelf (only meaningful together with [LibraryFilter.shelf]). */
enum class LibrarySort { TITLE, AUTHOR, SERIES, RATING, ADDED, RECENTLY_READ, SHELF }

// ---- Library --------------------------------------------------------------------------------------

/** Read side of the library, served from Room (works offline). */
interface LibraryRepository {
    fun books(accountIds: List<String>, filter: LibraryFilter = LibraryFilter()): Flow<List<LibraryBook>>
    fun book(key: BookKey): Flow<LibraryBook?>
    fun shelves(accountIds: List<String>): Flow<List<ShelfInfo>>
    fun series(accountIds: List<String>): Flow<List<SeriesInfo>>
    fun genres(accountIds: List<String>): Flow<List<FacetCount>>
    fun tags(accountIds: List<String>): Flow<List<FacetCount>>
    fun facets(accountIds: List<String>): Flow<LibraryFacets>
    /** Books to "continue reading": newest local progress first. */
    fun continueReading(accountIds: List<String>, limit: Int = 10): Flow<List<LibraryBook>>
}

// ---- Shelves -------------------------------------------------------------------------------------

/**
 * Shelf management. Unlike edits of books these calls need the server (shelves are server objects with
 * server ids); they throw [com.somecatcode.ebookreader.data.api.ApiException] and update Room on success.
 */
interface ShelfRepository {
    /** Reloads the shelf list and the membership of every manual shelf of the account. */
    suspend fun refresh(accountId: String)

    /** Reloads the membership of one manual shelf (opening a shelf). */
    suspend fun refreshMembers(key: ShelfKey)

    /** Creates a manual shelf ([query] null) or a smart shelf. Returns the new shelf. */
    suspend fun create(accountId: String, name: String, query: com.somecatcode.ebookreader.data.api.SmartQueryDto? = null): ShelfKey

    suspend fun rename(key: ShelfKey, name: String)
    suspend fun updateQuery(key: ShelfKey, query: com.somecatcode.ebookreader.data.api.SmartQueryDto)
    suspend fun delete(key: ShelfKey)

    /** Moves a shelf one place up (-1) or down (+1) in the list of its account. */
    suspend fun move(key: ShelfKey, delta: Int)

    suspend fun addBooks(key: ShelfKey, fileIds: List<Long>)
    suspend fun removeBooks(key: ShelfKey, fileIds: List<Long>)

    /** Ids of the manual shelves of the account that contain the book. */
    fun shelvesOf(book: BookKey): Flow<Set<Long>>
}

// ---- Progress -------------------------------------------------------------------------------------

/** Remote progress that differs from the local one. UI asks "Newer position from <device> - jump?". */
data class ProgressConflict(
    val key: BookKey,
    val localLocator: Locator,
    val localPercentage: Double,
    val remoteLocator: Locator,
    val remotePercentage: Double,
    val remoteDevice: String?,
    val remoteUpdatedAt: Long,
)

interface ProgressRepository {
    /** Local progress (what the reader opens at). */
    fun progress(key: BookKey): Flow<StoredProgress?>

    /**
     * Called by the reader on every `relocate` (debounced by the caller). Writes to Room with
     * `dirty = true`, `clientUpdatedAt = now` and schedules an upload; never blocks on the network.
     */
    suspend fun saveLocal(key: BookKey, locator: Locator, percentage: Double)

    /**
     * Before opening a book online: fetches the server progress; returns a conflict if the server
     * position is newer than the local one AND differs noticeably (> 1 % or other resource).
     * Offline or on error returns null (open at the local position).
     */
    suspend fun checkRemote(key: BookKey): ProgressConflict?

    /** User chose the remote position: stores it locally (not dirty). */
    suspend fun acceptRemote(conflict: ProgressConflict)

    /** User kept the local position: re-uploads it with a fresh `clientUpdatedAt` (wins at the server). */
    suspend fun keepLocal(conflict: ProgressConflict)

    /** Uploads all dirty progress rows (`/progress/batch`); 'conflict' results are stored as remote-newer without prompting if the book is not open. */
    suspend fun pushDirty(accountId: String)
}

data class StoredProgress(
    val key: BookKey,
    val locator: Locator,
    val percentage: Double,
    val device: String?,
    val clientUpdatedAt: Long,
    val dirty: Boolean,
)

// ---- Downloads ------------------------------------------------------------------------------------

data class OfflineItem(
    val key: BookKey,
    val title: String,
    val state: DownloadState,
    val bytes: Long,
    val total: Long,
    val pinnedBy: PinnedBy,
    val error: String?,
)

/** Which books to keep offline. */
sealed interface OfflineTarget {
    data class Book(val key: BookKey) : OfflineTarget
    data class Shelf(val key: ShelfKey) : OfflineTarget
    data class Series(val accountId: String, val name: String) : OfflineTarget
}

interface DownloadRepository {
    val items: Flow<List<OfflineItem>>

    /** Bytes used by finished downloads of all accounts. */
    val usedBytes: Flow<Long>

    /** Marks the target as offline and enqueues the missing files (new volumes of a pinned series/shelf are added by the sync). */
    suspend fun makeAvailableOffline(target: OfflineTarget)

    /** Removes the pin; files no other pin needs are deleted. */
    suspend fun removeOffline(target: OfflineTarget)

    suspend fun retry(key: BookKey)
    suspend fun cancel(key: BookKey)

    /** Deletes every local book file (keeps library data). */
    suspend fun clearAll()

    /** Local file of a finished download, or null. Used by the reader request proxy. */
    suspend fun localFile(key: BookKey): java.io.File?

    fun downloadEntity(key: BookKey): Flow<DownloadEntity?>
}

// ---- Editing --------------------------------------------------------------------------------------

/**
 * Editing is optimistic: the change is written to Room immediately (UI updates), stored in
 * `pending_edit`, and uploaded by [flushPending] (worker, after each sync, on connectivity). A
 * rejected edit (400/403/404) is dropped and reported through [failures]; transient errors retry.
 */
interface EditRepository {
    suspend fun editMetadata(key: BookKey, patch: MetadataPatch)
    suspend fun editAppData(key: BookKey, patch: AppDataPatch)

    /** Number of edits waiting for upload. */
    val pendingCount: Flow<Int>

    /** Edits the server rejected permanently (for a snackbar). */
    val failures: Flow<EditFailure>

    suspend fun flushPending(accountId: String)
}

data class EditFailure(val key: BookKey, val message: String)

// ---- Settings -------------------------------------------------------------------------------------

/** DataStore backed app settings. */
data class AppSettings(
    val syncIntervalHours: Int = 6,
    val syncOnlyOnWifi: Boolean = false,
    val downloadOnlyOnWifi: Boolean = true,
    val dynamicColor: Boolean = true,
    /** `system|light|dark` */
    val themeMode: String = "system",
    /** `grid|list` */
    val libraryLayout: String = "grid",
    /** Merged library of all accounts instead of one account at a time. */
    val mergedLibrary: Boolean = false,
    val lastAccountId: String? = null,
    val einkMode: Boolean = false,
    /** Reader defaults as JSON of `ReaderSettings` (see reader bridge). */
    val readerSettingsJson: String? = null,
    /** Device name sent with the progress (default: Build.MODEL). */
    val deviceName: String? = null,
    /** Library leaves out finished books unless a status filter is set (default on, like the web app). */
    val hideFinished: Boolean = true,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
