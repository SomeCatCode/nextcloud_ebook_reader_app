package com.somecatcode.ebookreader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room entities (PLAN.md section 3). One database for all accounts; every row carries `accountId`
 * and removing an account cascades through the foreign keys to all of its data.
 * JSON columns (authors, locator, patch, ...) hold kotlinx.serialization JSON written with ApiJson.
 * Timestamps are milliseconds since epoch.
 */

/** Persisted account. The app password is NOT stored here (see AccountStore). */
@Entity(tableName = "account")
data class AccountEntity(
    /** Local random UUID; stable even if the server URL changes. */
    @PrimaryKey val id: String,
    /** Base URL without trailing slash, e.g. `https://cloud.example.org/nextcloud`. */
    val serverUrl: String,
    val loginName: String,
    /** Nextcloud user id (used in WebDAV paths); may differ from the login name. */
    val userId: String,
    val displayName: String?,
    val serverVersion: String?,
    /** E-Book Reader app version if known, else null. */
    val appVersion: String?,
    /** Opaque `/sync` cursor; null/empty = full sync needed. */
    val lastSyncCursor: String?,
    val lastSyncAt: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "book",
    primaryKeys = ["accountId", "fileId"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("accountId", "title"),
        Index("accountId", "series", "seriesIndex"),
        Index("accountId", "updatedAt"),
    ],
)
data class BookEntity(
    val accountId: String,
    /** Nextcloud file id (server `fileId`). */
    val fileId: Long,
    /** `epub|mobi|azw3|fb2|fbz|cbz|cbr|cb7|cbt` */
    val format: String,
    /** User-relative server path, e.g. `/Books/x.epub`. */
    val path: String,
    val size: Long,
    val title: String?,
    /** JSON array of strings. */
    val authors: String,
    val series: String?,
    val seriesIndex: Double?,
    /** Sanitised HTML. */
    val description: String?,
    val language: String?,
    val publisher: String?,
    val isbn: String?,
    val publishedAt: String?,
    val rating: Int?,
    /** `unread|reading|finished` */
    val readStatus: String,
    val hasCover: Boolean,
    val coverEtag: String?,
    /** ETag of the book file (WebDAV) when known; the server JSON has none, set by the downloader. */
    val fileEtag: String?,
    val mtime: Long,
    val addedAt: Long,
    val updatedAt: Long,
    val editable: Boolean,
    val downloadable: Boolean,
    /** JSON array of field names edited in the app only. */
    val overrides: String,
    val hasSidecar: Boolean,
    /** Soft delete: set when the server reports the book as deleted; rows are purged after cleanup of the download. */
    val deleted: Boolean = false,
)

/** Genre or tag of a book. */
@Entity(
    tableName = "book_tag",
    primaryKeys = ["accountId", "fileId", "type", "name"],
    foreignKeys = [ForeignKey(BookEntity::class, ["accountId", "fileId"], ["accountId", "fileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "type", "name")],
)
data class BookTagEntity(
    val accountId: String,
    val fileId: Long,
    /** `genre` or `tag` */
    val type: String,
    val name: String,
)

@Entity(
    tableName = "progress",
    primaryKeys = ["accountId", "fileId"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "dirty")],
)
data class ProgressEntity(
    val accountId: String,
    val fileId: Long,
    /** JSON of the server `Locator`. */
    val locator: String,
    /** 0..1 */
    val percentage: Double,
    val device: String?,
    /** Client time of the last change (conflict rule: newer `clientUpdatedAt` wins). */
    val clientUpdatedAt: Long,
    /** Server time of the stored progress (0 while never uploaded). */
    val updatedAt: Long,
    /** Local change not yet uploaded. */
    val dirty: Boolean,
)

@Entity(
    tableName = "shelf",
    primaryKeys = ["accountId", "id"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
)
data class ShelfEntity(
    val accountId: String,
    /** Server shelf id. */
    val id: Long,
    val name: String,
    /** `manual|smart` */
    val type: String,
    /** JSON of `SmartQueryDto` for smart shelves, else null. */
    val query: String?,
    val count: Int,
    /** JSON array of up to 4 file ids. */
    val coverFileIds: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Membership of a MANUAL shelf. The server offers no membership list endpoint; it is resolved with
 * `GET /books?include[]=shelf:<name>` during sync. Smart shelves are evaluated on the server
 * (stored query) or locally from [ShelfEntity.query] and have no rows here.
 */
@Entity(
    tableName = "shelf_book",
    primaryKeys = ["accountId", "shelfId", "fileId"],
    foreignKeys = [ForeignKey(ShelfEntity::class, ["accountId", "id"], ["accountId", "shelfId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "fileId")],
)
data class ShelfBookEntity(
    val accountId: String,
    val shelfId: Long,
    val fileId: Long,
    val position: Int,
)

/** Local copy of a book file. One row per downloaded or queued book. */
@Entity(
    tableName = "download",
    primaryKeys = ["accountId", "fileId"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("state")],
)
data class DownloadEntity(
    val accountId: String,
    val fileId: Long,
    /** [DownloadState] name. */
    val state: String,
    val bytes: Long,
    val total: Long,
    /** Absolute path of the finished file (or the `.part` file while running); null while queued. */
    val localPath: String?,
    /** WebDAV ETag the local copy corresponds to. */
    val fileEtag: String?,
    /** [PinnedBy] name: why the book is kept offline. */
    val pinnedBy: String,
    /** Id of the shelf (as string) or the series name when pinned by one; null for a single book. */
    val pinRef: String?,
    val errorMessage: String?,
    val updatedAt: Long,
)

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED }

enum class PinnedBy { BOOK, SHELF, SERIES }

/** Metadata/app-data change made offline, uploaded by the EditRepository when online. */
@Entity(
    tableName = "pending_edit",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "fileId")],
)
data class PendingEditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val fileId: Long,
    /** `metadata` (PATCH /metadata) or `appData` (PATCH /app-data). */
    val kind: String,
    /** JSON body of the PATCH request. Later edits of the same kind and book are merged into one row. */
    val patch: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String?,
)

/**
 * Highlight, note or bookmark (server `ebookreader_annotations`). The [uuid] is generated by the
 * client that creates the annotation and is the id on every device, so local rows need no separate
 * server id. Offline first: local changes set [dirty] and are pushed by the AnnotationRepository;
 * a local delete keeps the row as a tombstone ([deleted]) until the server confirmed it.
 * No foreign key to `book`: the sync can deliver annotations before (or without) their book row.
 */
@Entity(
    tableName = "annotation",
    primaryKeys = ["accountId", "uuid"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "fileId"), Index("accountId", "dirty")],
)
data class AnnotationEntity(
    val accountId: String,
    /** Lower-case UUID, shared with the server and the web app. */
    val uuid: String,
    val fileId: Long,
    /** `highlight|note|bookmark` */
    val type: String,
    /** JSON of the server `Locator`; highlights/notes carry the range CFI in `locations.cfi`. */
    val locator: String,
    /** Selected text excerpt (max 2000 characters). */
    val text: String?,
    /** Note (max 10000 characters); a highlight with a note is shown as a note. */
    val note: String?,
    /** `yellow|green|blue|pink|purple` or null (bookmarks). */
    val color: String?,
    val createdAt: Long,
    /** Server time of the last stored change (0 while never uploaded). */
    val updatedAt: Long,
    /** Last-write-wins clock (server rule: the larger value wins, 409 otherwise). */
    val clientUpdatedAt: Long,
    /** Local delete waiting for the server (tombstone); never shown. */
    val deleted: Boolean = false,
    /** Local change not yet uploaded. */
    val dirty: Boolean = false,
)
