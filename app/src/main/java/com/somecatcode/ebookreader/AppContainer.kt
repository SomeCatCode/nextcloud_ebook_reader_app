package com.somecatcode.ebookreader

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import com.somecatcode.ebookreader.data.account.AccountStoreImpl
import com.somecatcode.ebookreader.data.account.CredentialCipher
import com.somecatcode.ebookreader.data.account.KeystoreCredentialCipher
import com.somecatcode.ebookreader.data.api.ApiClientFactoryImpl
import com.somecatcode.ebookreader.data.api.LoginFlowClientImpl
import com.somecatcode.ebookreader.data.download.DownloadManagerImpl
import com.somecatcode.ebookreader.data.download.DownloadStorage
import com.somecatcode.ebookreader.data.repo.DownloadRepositoryImpl
import com.somecatcode.ebookreader.data.repo.EditRepositoryImpl
import com.somecatcode.ebookreader.data.repo.LibraryRepositoryImpl
import com.somecatcode.ebookreader.data.repo.ProgressRepositoryImpl
import com.somecatcode.ebookreader.data.repo.SettingsRepositoryImpl
import com.somecatcode.ebookreader.data.repo.ShelfRepositoryImpl
import com.somecatcode.ebookreader.data.sync.LocalChangesScheduler
import com.somecatcode.ebookreader.data.sync.SyncEngineImpl
import com.somecatcode.ebookreader.data.sync.WorkManagerLocalChangesScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.LoginFlowClient
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.download.DownloadManager
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.EditRepository
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.ProgressRepository
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.data.repo.ShelfRepository
import com.somecatcode.ebookreader.data.sync.SyncEngine
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container (composition root). ViewModels receive the pieces they need from here
 * (through a factory); tests provide a fake implementation of this interface.
 *
 * Everything is created lazily, so cold start only pays for what a screen actually touches.
 */
interface AppContainer {
    val appContext: Context
    val json: Json
    val httpClient: OkHttpClient
    val database: AppDatabase

    val accountStore: AccountStore
    val loginFlowClient: LoginFlowClient
    val apiClientFactory: ApiClientFactory
    val libraryRepository: LibraryRepository
    val progressRepository: ProgressRepository
    val downloadRepository: DownloadRepository
    val editRepository: EditRepository
    val shelfRepository: ShelfRepository
    val settingsRepository: SettingsRepository
    val syncEngine: SyncEngine
    val downloadManager: DownloadManager
}

/** Production container with the real data layer (W-DATA). */
class DefaultAppContainer(override val appContext: Context) : AppContainer {

    override val json: Json = ApiJson

    override val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.FILE_NAME).build()
    }

    /** Process-wide scope for work that must outlive screens (scheduling, preference writes). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    private val downloadStorage = DownloadStorage { appContext.getExternalFilesDir("books") ?: File(appContext.filesDir, "books") }

    private val credentialCipher: CredentialCipher by lazy { KeystoreCredentialCipher() }

    private val settingsStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = appScope) { appContext.preferencesDataStoreFile("settings") }
    }

    private val localChangesScheduler: LocalChangesScheduler by lazy { WorkManagerLocalChangesScheduler { workManager } }

    private val apiClientFactoryImpl: ApiClientFactoryImpl by lazy { ApiClientFactoryImpl(accountStore, httpClient) }

    override val accountStore: AccountStore by lazy {
        AccountStoreImpl(
            db = database,
            cipher = credentialCipher,
            credentialsDir = File(appContext.noBackupFilesDir, "credentials"),
            booksRoot = { downloadStorage.root },
            beforeRemove = { id ->
                runCatching {
                    workManager.cancelUniqueWork("sync-$id")
                    workManager.cancelUniqueWork("push-$id")
                }
                (downloadManager as? DownloadManagerImpl)?.cancelAccount(id)
                apiClientFactoryImpl.forget(id)
            },
        )
    }
    override val loginFlowClient: LoginFlowClient by lazy { LoginFlowClientImpl(httpClient) }
    override val apiClientFactory: ApiClientFactory get() = apiClientFactoryImpl
    override val libraryRepository: LibraryRepository by lazy { LibraryRepositoryImpl(database) }
    override val progressRepository: ProgressRepository by lazy {
        ProgressRepositoryImpl(database, apiClientFactory, settingsRepository, localChangesScheduler, { Build.MODEL ?: "Android" })
    }
    override val downloadRepository: DownloadRepository by lazy { DownloadRepositoryImpl(database, downloadManager) }
    override val editRepository: EditRepository by lazy { EditRepositoryImpl(database, apiClientFactory, localChangesScheduler) }
    override val shelfRepository: ShelfRepository by lazy { ShelfRepositoryImpl(database, apiClientFactory) }
    override val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(settingsStore) }
    override val syncEngine: SyncEngine by lazy {
        SyncEngineImpl(database, apiClientFactory, editRepository, progressRepository, downloadManager, { workManager }, appScope)
    }
    override val downloadManager: DownloadManager by lazy {
        DownloadManagerImpl(database, apiClientFactory, settingsRepository, downloadStorage, { workManager })
    }

    /**
     * Starts the background machinery once per process: periodic sync follows the settings and the
     * account list, interrupted downloads are resumed. Failures are ignored (retried at the next start).
     */
    fun startBackgroundWork() {
        appScope.launch {
            runCatching { downloadManager.resumePending() }
        }
        appScope.launch {
            runCatching {
                combine(
                    settingsRepository.settings.map { it.syncIntervalHours to it.syncOnlyOnWifi },
                    accountStore.accounts.map { it.size },
                ) { prefs, count -> Triple(prefs.first, prefs.second, count) }
                    .distinctUntilChanged()
                    .collect { (hours, wifi, _) -> syncEngine.schedulePeriodic(hours, wifi) }
            }
        }
    }
}
