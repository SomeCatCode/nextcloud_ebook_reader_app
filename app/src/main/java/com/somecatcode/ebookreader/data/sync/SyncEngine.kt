package com.somecatcode.ebookreader.data.sync

import kotlinx.coroutines.flow.Flow

/**
 * Library synchronisation per account (PLAN.md section 2, WorkManager based).
 *
 * One sync run for an account:
 * 1. `EditRepository.flushPending`, `ProgressRepository.pushDirty` and `AnnotationRepository.pushDirty`
 *    (local changes first).
 * 2. `GET /sync?cursor=<lastSyncCursor>` repeatedly while `hasMore`; upsert books (+ tags), apply
 *    `deleted` (mark deleted, remove downloads), merge `progress` (server wins unless the local row is
 *    dirty and has a newer `clientUpdatedAt`), merge `annotations` by uuid (tombstones remove the local row,
 *    otherwise the server row wins unless the local one is dirty with a newer `clientUpdatedAt`). Persist the cursor only after the page was written
 *    (single Room transaction per page) so an interrupted run resumes cleanly.
 * 3. Refresh shelves (`/shelves`, membership of manual shelves via `/books?include[]=shelf:`).
 * 4. Enqueue downloads for new books of pinned shelves/series (`DownloadManager`).
 *
 * A failed cursor (HTTP 400 "invalid cursor") resets to a full sync. WorkManager unique work names:
 * `sync-<accountId>` (one-time/manual, expedited when started by the user) and `sync-periodic`
 * (all accounts, interval from AppSettings, constraint NetworkType.CONNECTED or UNMETERED).
 *
 * Owner: W-DATA (`data/sync/SyncEngineImpl.kt`, `SyncWorker.kt`).
 */
interface SyncEngine {
    /** Current state per account id (absent = idle, never synced in this process). */
    val state: Flow<Map<String, SyncState>>

    /** Runs a sync now in the caller's coroutine (used by the worker and by tests). */
    suspend fun syncNow(accountId: String): SyncOutcome

    /** Enqueues a manual one-time sync through WorkManager (pull-to-refresh, app start). `accountId` null = all accounts. */
    fun requestSync(accountId: String? = null)

    /** (Re)schedules the periodic background sync; cancels it when there are no accounts. */
    fun schedulePeriodic(intervalHours: Int, wifiOnly: Boolean)
}

sealed interface SyncState {
    data object Idle : SyncState
    /** [phase] 0..1 is best effort. */
    data class Running(val phase: SyncPhase, val progress: Float? = null) : SyncState
    data class Failed(val error: SyncError, val at: Long) : SyncState
}

enum class SyncPhase { PUSH_LOCAL, BOOKS, SHELVES, DOWNLOADS }

enum class SyncError {
    /** No connection / timeout; shown as "offline", not as an error. */
    OFFLINE,
    /** App password revoked: the account needs a new login. */
    UNAUTHORIZED,
    /** E-Book Reader app missing or older than 0.5.0. */
    APP_UNAVAILABLE,
    SERVER,
    UNKNOWN,
}

sealed interface SyncOutcome {
    data class Success(val booksChanged: Int, val booksDeleted: Int, val progressMerged: Int, val annotationsMerged: Int = 0) : SyncOutcome
    data class Failure(val error: SyncError, val retryable: Boolean) : SyncOutcome
}
