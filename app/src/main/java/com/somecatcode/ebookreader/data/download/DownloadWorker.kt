package com.somecatcode.ebookreader.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.somecatcode.ebookreader.App
import com.somecatcode.ebookreader.data.DataNotifications
import com.somecatcode.ebookreader.data.repo.BookKey
import kotlinx.coroutines.CancellationException

/** Downloads one book (input: account id + file id) through [DownloadManagerImpl.runDownload]. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val fileId = inputData.getLong(KEY_FILE, -1L).takeIf { it >= 0 } ?: return Result.failure()
        val manager = (applicationContext as App).container.downloadManager as? DownloadManagerImpl
            ?: return Result.failure()
        val key = BookKey(accountId, fileId)
        val notificationId = DataNotifications.DOWNLOAD_NOTIFICATION_BASE + (key.hashCode() and 0x3ff)
        var foreground = false

        val run = try {
            manager.runDownload(key, runAttemptCount) { title, bytes, total ->
                if (total >= LARGE_DOWNLOAD_BYTES) {
                    // Large file: keep the process alive with an ongoing notification (type dataSync).
                    // May be refused when started from the background without exemption; then we just continue.
                    runCatching {
                        setForeground(DataNotifications.downloadInfo(applicationContext, notificationId, title, bytes, total))
                        foreground = true
                    }.onFailure { if (it is CancellationException) throw it }
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
        return when (run) {
            DownloadRun.Done, DownloadRun.Gone -> Result.success()
            is DownloadRun.Retry -> Result.retry()
            is DownloadRun.Failed -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        DataNotifications.downloadInfo(applicationContext, DataNotifications.DOWNLOAD_NOTIFICATION_BASE, "", 0, 0)

    companion object {
        const val KEY_ACCOUNT = "accountId"
        const val KEY_FILE = "fileId"

        /** From this size on the download runs as a foreground service with a notification. */
        const val LARGE_DOWNLOAD_BYTES = 20L * 1024 * 1024
    }
}
