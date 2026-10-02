package com.somecatcode.ebookreader

import android.content.Context
import androidx.room.Room
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
import com.somecatcode.ebookreader.data.sync.SyncEngine
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container (composition root). ViewModels receive the pieces they need from here
 * (through a factory); tests provide a fake implementation of this interface.
 *
 * Everything that is not implemented yet is created lazily and throws [NotImplementedError] when
 * first touched, so the skeleton compiles and starts while the data layer is being built.
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
    val settingsRepository: SettingsRepository
    val syncEngine: SyncEngine
    val downloadManager: DownloadManager
}

/** Production container. W-DATA replaces the `notYet(...)` placeholders with the real implementations. */
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

    override val accountStore: AccountStore by lazy { notYet("AccountStore") }
    override val loginFlowClient: LoginFlowClient by lazy { notYet("LoginFlowClient") }
    override val apiClientFactory: ApiClientFactory by lazy { notYet("ApiClientFactory") }
    override val libraryRepository: LibraryRepository by lazy { notYet("LibraryRepository") }
    override val progressRepository: ProgressRepository by lazy { notYet("ProgressRepository") }
    override val downloadRepository: DownloadRepository by lazy { notYet("DownloadRepository") }
    override val editRepository: EditRepository by lazy { notYet("EditRepository") }
    override val settingsRepository: SettingsRepository by lazy { notYet("SettingsRepository") }
    override val syncEngine: SyncEngine by lazy { notYet("SyncEngine") }
    override val downloadManager: DownloadManager by lazy { notYet("DownloadManager") }

    private fun notYet(what: String): Nothing =
        throw NotImplementedError("$what is not implemented yet (see docs/CONTRACTS.md)")
}
