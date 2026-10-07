package com.somecatcode.ebookreader.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.somecatcode.ebookreader.App
import com.somecatcode.ebookreader.data.DataNotifications
import java.util.concurrent.TimeUnit

/** Schedules the upload of local changes (edits, reading progress, annotations) once connectivity is there. */
interface LocalChangesScheduler {
    fun schedule(accountId: String)
}

/** [LocalChangesScheduler] on WorkManager: unique work `push-<accountId>`, runs after a short delay so bursts collapse. */
class WorkManagerLocalChangesScheduler(private val workManager: () -> WorkManager) : LocalChangesScheduler {
    override fun schedule(accountId: String) {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_ACCOUNT to accountId))
            .setInitialDelay(PUSH_DELAY_SECONDS, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching { workManager().enqueueUniqueWork("push-$accountId", ExistingWorkPolicy.REPLACE, request) }
    }

    private companion object {
        const val PUSH_DELAY_SECONDS = 20L
    }
}

/** Uploads pending edits, dirty progress and annotations of one account. */
class PushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(SyncWorker.KEY_ACCOUNT) ?: return Result.failure()
        val container = (applicationContext as App).container
        container.editRepository.flushPending(accountId)
        container.progressRepository.pushDirty(accountId)
        container.annotationRepository.pushDirty(accountId)
        return Result.success()
    }
}

/** One-off sync of one account (`sync-<accountId>`), expedited when the user asked for it. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val engine = (applicationContext as App).container.syncEngine
        return when (val outcome = engine.syncNow(accountId)) {
            is SyncOutcome.Success -> Result.success()
            is SyncOutcome.Failure -> if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = DataNotifications.syncInfo(applicationContext)

    companion object {
        const val KEY_ACCOUNT = "accountId"
        private const val MAX_ATTEMPTS = 4
    }
}

/** Periodic sync (`sync-periodic`) of all accounts. */
class PeriodicSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        val ids = container.accountStore.list().map { it.id }
        var retry = false
        for (id in ids) {
            val outcome = container.syncEngine.syncNow(id)
            if (outcome is SyncOutcome.Failure && outcome.retryable && outcome.error != SyncError.OFFLINE) retry = true
        }
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
