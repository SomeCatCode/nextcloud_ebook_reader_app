package com.somecatcode.ebookreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY createdAt")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY createdAt")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE account SET lastSyncCursor = :cursor, lastSyncAt = :at WHERE id = :id")
    suspend fun updateSync(id: String, cursor: String?, at: Long?)

    /** Nextcloud version and E-Book Reader app version as last reported by the server (null = unknown). */
    @Query("UPDATE account SET serverVersion = :serverVersion, appVersion = :appVersion WHERE id = :id")
    suspend fun updateVersions(id: String, serverVersion: String?, appVersion: String?)

    /** Cascades to all rows of the account (books, progress, shelves, downloads, pending edits). */
    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BookDao {

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("SELECT * FROM book WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun get(accountId: String, fileId: Long): BookEntity?

    @Query("SELECT * FROM book WHERE accountId = :accountId AND fileId = :fileId")
    fun observe(accountId: String, fileId: Long): Flow<BookEntity?>

    /**
     * Library list for one or several accounts (merged view). `search` matches title, authors and
     * series (LIKE, case-insensitive); `status` null = any. Sorted by title; other sort orders and
     * tag filters are added by W-DATA with additional queries or a `@RawQuery`.
     */
    @Query(
        """
        SELECT * FROM book
        WHERE accountId IN (:accountIds) AND deleted = 0
          AND (:status IS NULL OR readStatus = :status)
          AND (:search IS NULL OR title LIKE '%' || :search || '%' OR authors LIKE '%' || :search || '%' OR series LIKE '%' || :search || '%')
        ORDER BY title COLLATE NOCASE, accountId, fileId
        """,
    )
    fun observeLibrary(accountIds: List<String>, status: String?, search: String?): Flow<List<BookEntity>>

    @Query("SELECT * FROM book WHERE accountId = :accountId AND series = :series AND deleted = 0 ORDER BY seriesIndex, title COLLATE NOCASE")
    fun observeSeries(accountId: String, series: String): Flow<List<BookEntity>>

    @Query(
        """
        SELECT series AS name, COUNT(*) AS count,
               SUM(CASE WHEN readStatus = 'finished' THEN 1 ELSE 0 END) AS readCount,
               MIN(fileId) AS firstFileId, MAX(addedAt) AS lastAddedAt
        FROM book
        WHERE accountId = :accountId AND deleted = 0 AND series IS NOT NULL AND series != ''
        GROUP BY series ORDER BY series COLLATE NOCASE
        """,
    )
    fun observeSeriesSummaries(accountId: String): Flow<List<SeriesSummary>>

    @Query("UPDATE book SET deleted = 1 WHERE accountId = :accountId AND fileId IN (:fileIds)")
    suspend fun markDeleted(accountId: String, fileIds: List<Long>)

    @Query("DELETE FROM book WHERE accountId = :accountId AND deleted = 1")
    suspend fun purgeDeleted(accountId: String)

    @Query("UPDATE book SET rating = :rating, readStatus = :readStatus WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun updateAppData(accountId: String, fileId: Long, rating: Int?, readStatus: String)

    @Query("SELECT * FROM book WHERE accountId = :accountId AND deleted = 0")
    suspend fun getAllActive(accountId: String): List<BookEntity>

    @Query("SELECT * FROM book WHERE accountId = :accountId AND fileId IN (:fileIds)")
    suspend fun getByIds(accountId: String, fileIds: List<Long>): List<BookEntity>

    @Query("SELECT fileId FROM book WHERE accountId = :accountId AND deleted = 0")
    suspend fun activeFileIds(accountId: String): List<Long>

    @Query("UPDATE book SET fileEtag = :etag WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun updateFileEtag(accountId: String, fileId: Long, etag: String?)

    @Query("SELECT COUNT(*) FROM book WHERE accountId = :accountId AND deleted = 0")
    suspend fun count(accountId: String): Int
}

data class SeriesSummary(
    val name: String,
    val count: Int,
    val readCount: Int,
    val firstFileId: Long,
    val lastAddedAt: Long,
)

@Dao
interface BookTagDao {
    @Query("SELECT * FROM book_tag WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun forBook(accountId: String, fileId: Long): List<BookTagEntity>

    @Query("SELECT * FROM book_tag WHERE accountId IN (:accountIds)")
    fun observeAll(accountIds: List<String>): Flow<List<BookTagEntity>>

    @Query("SELECT name, COUNT(*) AS count FROM book_tag WHERE accountId IN (:accountIds) AND type = :type GROUP BY name ORDER BY name COLLATE NOCASE")
    fun observeFacet(accountIds: List<String>, type: String): Flow<List<TagCount>>

    @Query("SELECT * FROM book_tag WHERE accountId = :accountId AND fileId = :fileId")
    fun observeForBook(accountId: String, fileId: Long): Flow<List<BookTagEntity>>

    @Query("SELECT * FROM book_tag WHERE accountId = :accountId AND fileId IN (:fileIds)")
    suspend fun forBooks(accountId: String, fileIds: List<Long>): List<BookTagEntity>

    @Query("SELECT * FROM book_tag WHERE accountId = :accountId")
    suspend fun allOf(accountId: String): List<BookTagEntity>

    @Query("DELETE FROM book_tag WHERE accountId = :accountId AND fileId IN (:fileIds)")
    suspend fun deleteForBooks(accountId: String, fileIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<BookTagEntity>)

    @Query("DELETE FROM book_tag WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun deleteForBook(accountId: String, fileId: Long)

    /** Replaces genres and tags of a book atomically. */
    @Transaction
    suspend fun replaceForBook(accountId: String, fileId: Long, tags: List<BookTagEntity>) {
        deleteForBook(accountId, fileId)
        insertAll(tags)
    }
}

data class TagCount(val name: String, val count: Int)

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun get(accountId: String, fileId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE accountId = :accountId AND fileId = :fileId")
    fun observe(accountId: String, fileId: Long): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE accountId IN (:accountIds)")
    fun observeAll(accountIds: List<String>): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE accountId = :accountId AND dirty = 1 ORDER BY clientUpdatedAt")
    suspend fun dirty(accountId: String): List<ProgressEntity>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Upsert
    suspend fun upsertAll(progress: List<ProgressEntity>)

    @Query("DELETE FROM progress WHERE accountId = :accountId AND fileId IN (:fileIds)")
    suspend fun deleteForBooks(accountId: String, fileIds: List<Long>)

    @Query("SELECT COUNT(*) FROM progress WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>
}

@Dao
interface ShelfDao {
    @Query("SELECT * FROM shelf WHERE accountId IN (:accountIds) ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(accountIds: List<String>): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelf WHERE accountId = :accountId")
    suspend fun getAll(accountId: String): List<ShelfEntity>

    @Query("SELECT fileId FROM shelf_book WHERE accountId = :accountId AND shelfId = :shelfId ORDER BY position")
    suspend fun fileIds(accountId: String, shelfId: Long): List<Long>

    @Query("SELECT * FROM shelf_book WHERE accountId = :accountId AND shelfId = :shelfId ORDER BY position")
    fun observeMembers(accountId: String, shelfId: Long): Flow<List<ShelfBookEntity>>

    @Upsert
    suspend fun upsertShelves(shelves: List<ShelfEntity>)

    @Query("SELECT * FROM shelf WHERE accountId = :accountId AND id = :shelfId")
    suspend fun get(accountId: String, shelfId: Long): ShelfEntity?

    @Query("SELECT * FROM shelf_book WHERE accountId IN (:accountIds)")
    fun observeAllMembers(accountIds: List<String>): Flow<List<ShelfBookEntity>>

    @Query("SELECT * FROM shelf_book WHERE accountId = :accountId")
    suspend fun allMembers(accountId: String): List<ShelfBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<ShelfBookEntity>)

    @Query("DELETE FROM shelf_book WHERE accountId = :accountId AND shelfId = :shelfId")
    suspend fun clearMembers(accountId: String, shelfId: Long)

    @Query("DELETE FROM shelf WHERE accountId = :accountId AND id = :shelfId")
    suspend fun deleteOne(accountId: String, shelfId: Long)

    @Query("DELETE FROM shelf WHERE accountId = :accountId AND id NOT IN (:keepIds)")
    suspend fun deleteExcept(accountId: String, keepIds: List<Long>)

    @Transaction
    suspend fun replaceMembers(accountId: String, shelfId: Long, members: List<ShelfBookEntity>) {
        clearMembers(accountId, shelfId)
        insertMembers(members)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download WHERE accountId IN (:accountIds)")
    fun observeAll(accountIds: List<String>): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download")
    fun observeEvery(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun get(accountId: String, fileId: Long): DownloadEntity?

    @Query("SELECT * FROM download WHERE accountId = :accountId AND fileId = :fileId")
    fun observe(accountId: String, fileId: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM download WHERE state IN (:states) ORDER BY updatedAt")
    suspend fun inStates(states: List<String>): List<DownloadEntity>

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE download SET bytes = :bytes, total = :total, state = :state, updatedAt = :now WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun updateProgress(accountId: String, fileId: Long, bytes: Long, total: Long, state: String, now: Long)

    @Query("DELETE FROM download WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun delete(accountId: String, fileId: Long)

    @Query("SELECT * FROM download WHERE accountId = :accountId")
    suspend fun forAccount(accountId: String): List<DownloadEntity>

    @Query("SELECT * FROM download")
    suspend fun all(): List<DownloadEntity>

    @Query("UPDATE download SET state = :state, errorMessage = :error, updatedAt = :now WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun updateState(accountId: String, fileId: Long, state: String, error: String?, now: Long)

    /** Downloads joined with the book title for the offline list. */
    @Query(
        """
        SELECT d.accountId AS accountId, d.fileId AS fileId, b.title AS title, b.path AS path, d.state AS state,
               d.bytes AS bytes, d.total AS total, d.pinnedBy AS pinnedBy, d.errorMessage AS errorMessage
        FROM download d LEFT JOIN book b ON b.accountId = d.accountId AND b.fileId = d.fileId
        ORDER BY d.updatedAt DESC
        """,
    )
    fun observeItems(): Flow<List<DownloadItemRow>>

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM download WHERE state = 'DONE'")
    fun observeUsedBytes(): Flow<Long>
}

data class DownloadItemRow(
    val accountId: String,
    val fileId: Long,
    val title: String?,
    val path: String?,
    val state: String,
    val bytes: Long,
    val total: Long,
    val pinnedBy: String,
    val errorMessage: String?,
)

@Dao
interface PendingEditDao {
    @Query("SELECT * FROM pending_edit WHERE accountId = :accountId ORDER BY id")
    suspend fun forAccount(accountId: String): List<PendingEditEntity>

    @Query("SELECT * FROM pending_edit WHERE accountId = :accountId AND fileId = :fileId AND kind = :kind LIMIT 1")
    suspend fun find(accountId: String, fileId: Long, kind: String): PendingEditEntity?

    @Query("SELECT * FROM pending_edit WHERE accountId IN (:accountIds)")
    fun observeAll(accountIds: List<String>): Flow<List<PendingEditEntity>>

    @Query("SELECT COUNT(*) FROM pending_edit WHERE accountId = :accountId AND fileId = :fileId")
    fun observeCountForBook(accountId: String, fileId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_edit WHERE accountId = :accountId AND fileId = :fileId")
    suspend fun countForBook(accountId: String, fileId: Long): Int

    @Query("SELECT * FROM pending_edit WHERE id = :id")
    suspend fun get(id: Long): PendingEditEntity?

    @Query("DELETE FROM pending_edit WHERE accountId = :accountId AND fileId IN (:fileIds)")
    suspend fun deleteForBooks(accountId: String, fileIds: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_edit")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(edit: PendingEditEntity)

    @Query("DELETE FROM pending_edit WHERE id = :id")
    suspend fun delete(id: Long)
}
