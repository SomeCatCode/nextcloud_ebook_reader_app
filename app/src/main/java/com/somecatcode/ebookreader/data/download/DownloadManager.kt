package com.somecatcode.ebookreader.data.download

import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.repo.BookKey
import java.io.File

/**
 * Downloads book files over WebDAV (`GET /remote.php/dav/files/{userId}/{path}`) in WorkManager
 * workers. Rules:
 * - Target: `<storage root>/<accountId>/<fileId>.<format>`; storage root is
 *   `Context.getExternalFilesDir("books")` (app-private, removed on uninstall), fallback `filesDir/books`.
 * - Written to `<name>.part`, resumed with `Range: bytes=<size>-` plus `If-Range: <etag>`; a 200 answer
 *   (range ignored / file changed) restarts from zero. Atomic rename when complete; size and, if the
 *   server sent one, the ETag are verified.
 * - Max 2 parallel downloads; constraint NetworkType.CONNECTED or UNMETERED (AppSettings.downloadOnlyOnWifi).
 * - A finished copy whose server `mtime`/size changed (seen by sync) is re-downloaded.
 * - State and progress go to the `download` table; the UI only observes Room.
 *
 * Owner: W-DATA (`data/download/DownloadManagerImpl.kt`, `DownloadWorker.kt`).
 */
interface DownloadManager {
    /** Queues the download (idempotent; no-op if DONE and up to date). Pins are merged: DONE stays DONE. */
    suspend fun enqueue(key: BookKey, pinnedBy: PinnedBy, pinRef: String? = null)

    /** Cancels a queued/running download and deletes the partial file. */
    suspend fun cancel(key: BookKey)

    /** Deletes the local file and the `download` row. */
    suspend fun delete(key: BookKey)

    /** The finished local file, or null if not downloaded (or the file vanished: the row is then reset). */
    suspend fun localFile(key: BookKey): File?

    /** Re-queues all FAILED rows and resumes QUEUED/RUNNING rows after process death (called at app start and from the sync). */
    suspend fun resumePending()

    /** Free space of the storage volume in bytes (for the storage screen and a pre-check). */
    fun freeBytes(): Long
}
