package com.somecatcode.ebookreader.ui

import android.content.Context
import com.somecatcode.ebookreader.AppContainer
import com.somecatcode.ebookreader.data.account.Account
import com.somecatcode.ebookreader.data.account.AccountAlreadyExistsException
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.account.Credentials
import com.somecatcode.ebookreader.data.account.NewAccount
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.ApiJson
import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.CloudUser
import com.somecatcode.ebookreader.data.api.EbookApi
import com.somecatcode.ebookreader.data.api.EbookReaderCapabilities
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.api.LoginFlowClient
import com.somecatcode.ebookreader.data.api.LoginFlowPoll
import com.somecatcode.ebookreader.data.api.LoginFlowResult
import com.somecatcode.ebookreader.data.api.LoginFlowStart
import com.somecatcode.ebookreader.data.api.MetadataPatch
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.api.ServerCompatibility
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.download.DownloadManager
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.EditFailure
import com.somecatcode.ebookreader.data.repo.EditRepository
import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibraryFilter
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.OfflineItem
import com.somecatcode.ebookreader.data.repo.OfflineState
import com.somecatcode.ebookreader.data.repo.OfflineTarget
import com.somecatcode.ebookreader.data.repo.ProgressConflict
import com.somecatcode.ebookreader.data.repo.ProgressRepository
import com.somecatcode.ebookreader.data.repo.SeriesInfo
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.data.repo.StoredProgress
import com.somecatcode.ebookreader.data.sync.SyncEngine
import com.somecatcode.ebookreader.data.sync.SyncOutcome
import com.somecatcode.ebookreader.data.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.lang.reflect.Proxy

// ---- sample data ------------------------------------------------------------------------------------

fun account(id: String = "acc1", name: String = "Alice", server: String = "https://cloud.example.org") =
    Account(id, server, name.lowercase(), name.lowercase(), name, "31.0.0", "1", null)

fun book(
    fileId: Long,
    title: String,
    accountId: String = "acc1",
    format: String = "epub",
    authors: List<String> = listOf("Author $fileId"),
    series: String? = null,
    seriesIndex: Double? = null,
    genres: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    status: ReadStatus = ReadStatus.UNREAD,
    rating: Int? = null,
    offline: OfflineState = OfflineState(null),
    editable: Boolean = true,
    percentage: Double? = null,
    pending: Boolean = false,
) = LibraryBook(
    key = BookKey(accountId, fileId), format = format, path = "/Books/$title.$format", size = 1_000_000, title = title,
    authors = authors, series = series, seriesIndex = seriesIndex, descriptionHtml = null, language = "en", publisher = null,
    isbn = null, publishedAt = null, genres = genres, tags = tags, rating = rating, readStatus = status, hasCover = false,
    coverEtag = null, addedAt = fileId, editable = editable, downloadable = true, percentage = percentage, offline = offline,
    hasPendingEdit = pending,
)

// ---- repositories -----------------------------------------------------------------------------------

class FakeAccountStore(initial: List<Account> = emptyList(), private val missingCredentials: Set<String> = emptySet()) : AccountStore {
    val state = MutableStateFlow(initial)
    var added: NewAccount? = null
    var updatedCredentials: Pair<String, Credentials>? = null
    val removed = mutableListOf<String>()
    override val accounts: Flow<List<Account>> = state
    override suspend fun list() = state.value
    override suspend fun get(accountId: String) = state.value.firstOrNull { it.id == accountId }
    override suspend fun add(account: NewAccount): Account {
        state.value.firstOrNull { it.serverUrl == account.serverUrl && it.userId == account.userId }?.let { throw AccountAlreadyExistsException(it) }
        added = account
        return Account("new", account.serverUrl, account.loginName, account.userId, account.displayName, account.serverVersion, account.appVersion, null)
            .also { state.value += it }
    }
    override suspend fun updateCredentials(accountId: String, credentials: Credentials) { updatedCredentials = accountId to credentials }
    override suspend fun credentials(accountId: String) = if (accountId in missingCredentials) null else Credentials("u", "p")
    override suspend fun remove(accountId: String) { removed += accountId; state.value = state.value.filterNot { it.id == accountId } }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

class FakeSyncEngine : SyncEngine {
    val syncState = MutableStateFlow<Map<String, SyncState>>(emptyMap())
    val requested = mutableListOf<String?>()
    override val state: Flow<Map<String, SyncState>> = syncState
    override suspend fun syncNow(accountId: String): SyncOutcome = SyncOutcome.Success(0, 0, 0)
    override fun requestSync(accountId: String?) { requested += accountId }
    override fun schedulePeriodic(intervalHours: Int, wifiOnly: Boolean) = Unit
}

class FakeLibraryRepository(initial: List<LibraryBook> = emptyList()) : LibraryRepository {
    val allBooks = MutableStateFlow(initial)
    val genreFacets = MutableStateFlow(listOf(FacetCount("Fantasy", 3), FacetCount("Sci-Fi", 2)))
    val tagFacets = MutableStateFlow(listOf(FacetCount("favorite", 1)))
    val shelfList = MutableStateFlow<List<ShelfInfo>>(emptyList())
    val seriesList = MutableStateFlow<List<SeriesInfo>>(emptyList())
    val lastFilter = MutableStateFlow(LibraryFilter())

    override fun books(accountIds: List<String>, filter: LibraryFilter): Flow<List<LibraryBook>> {
        lastFilter.value = filter
        return allBooks.map { list ->
            list.filter { it.key.accountId in accountIds }
                .filter { filter.search.isNullOrBlank() || it.title.contains(filter.search!!, ignoreCase = true) }
                .filter { filter.status == null || it.readStatus == filter.status }
                .filter { !filter.onlyOffline || it.offline.isAvailableOffline }
                .filter { filter.genres.isEmpty() || it.genres.any { g -> g in filter.genres } }
                .filter { filter.series == null || it.series == filter.series }
                .sortedBy { it.title.lowercase() }
        }
    }
    override fun book(key: BookKey): Flow<LibraryBook?> = allBooks.map { list -> list.firstOrNull { it.key == key } }
    override fun shelves(accountIds: List<String>): Flow<List<ShelfInfo>> = shelfList
    override fun series(accountIds: List<String>): Flow<List<SeriesInfo>> = seriesList
    override fun genres(accountIds: List<String>): Flow<List<FacetCount>> = genreFacets
    override fun tags(accountIds: List<String>): Flow<List<FacetCount>> = tagFacets
    override fun continueReading(accountIds: List<String>, limit: Int): Flow<List<LibraryBook>> = MutableStateFlow(emptyList())
}

class FakeEditRepository : EditRepository {
    val metadataEdits = mutableListOf<Pair<BookKey, MetadataPatch>>()
    val appDataEdits = mutableListOf<Pair<BookKey, AppDataPatch>>()
    val failureFlow = MutableSharedFlow<EditFailure>(extraBufferCapacity = 4)
    override suspend fun editMetadata(key: BookKey, patch: MetadataPatch) { metadataEdits += key to patch }
    override suspend fun editAppData(key: BookKey, patch: AppDataPatch) { appDataEdits += key to patch }
    override val pendingCount: Flow<Int> = MutableStateFlow(0)
    override val failures: Flow<EditFailure> = failureFlow
    override suspend fun flushPending(accountId: String) = Unit
}

class FakeProgressRepository(
    var conflict: ProgressConflict? = null,
    var local: StoredProgress? = null,
) : ProgressRepository {
    val saved = mutableListOf<Triple<BookKey, Locator, Double>>()
    var accepted: ProgressConflict? = null
    var kept: ProgressConflict? = null
    override fun progress(key: BookKey): Flow<StoredProgress?> = MutableStateFlow(local)
    override suspend fun saveLocal(key: BookKey, locator: Locator, percentage: Double) { saved += Triple(key, locator, percentage) }
    override suspend fun checkRemote(key: BookKey) = conflict
    override suspend fun acceptRemote(conflict: ProgressConflict) { accepted = conflict }
    override suspend fun keepLocal(conflict: ProgressConflict) { kept = conflict }
    override suspend fun pushDirty(accountId: String) = Unit
}

class FakeDownloadRepository(private val localFile: File? = null) : DownloadRepository {
    val itemsState = MutableStateFlow<List<OfflineItem>>(emptyList())
    val used = MutableStateFlow(0L)
    val made = mutableListOf<OfflineTarget>()
    val removedTargets = mutableListOf<OfflineTarget>()
    var clearedAll = false
    override val items: Flow<List<OfflineItem>> = itemsState
    override val usedBytes: Flow<Long> = used
    override suspend fun makeAvailableOffline(target: OfflineTarget) { made += target }
    override suspend fun removeOffline(target: OfflineTarget) { removedTargets += target }
    override suspend fun retry(key: BookKey) = Unit
    override suspend fun cancel(key: BookKey) = Unit
    override suspend fun clearAll() { clearedAll = true }
    override suspend fun localFile(key: BookKey): File? = localFile
    override fun downloadEntity(key: BookKey): Flow<DownloadEntity?> = MutableStateFlow(null)
}

// ---- api / login ------------------------------------------------------------------------------------

class FakeLoginFlowClient : LoginFlowClient {
    var startError: Throwable? = null
    var result: LoginFlowResult? = LoginFlowResult("https://cloud.example.org", "alice", "app-password")
    val started = mutableListOf<String>()
    override suspend fun start(serverInput: String): LoginFlowStart {
        started += serverInput
        startError?.let { throw it }
        return LoginFlowStart(LoginFlowPoll("t", "$serverInput/poll"), "$serverInput/login/flow")
    }
    override suspend fun pollOnce(poll: LoginFlowPoll): LoginFlowResult? = result
}

/** [EbookApi] stub answering only what the account flow needs; everything else throws. */
class ApiStub(
    var compatibility: ServerCompatibility = ServerCompatibility.Ok(EbookReaderCapabilities(apiVersion = 1), "31.0.0"),
    var userId: String = "alice",
    var revokeFails: Boolean = false,
) {
    var revoked = 0
    val api: EbookApi = Proxy.newProxyInstance(EbookApi::class.java.classLoader, arrayOf(EbookApi::class.java)) { _, method, _ ->
        when (method.name) {
            "checkCompatibility" -> compatibility
            "currentUser" -> CloudUser(userId, "Alice")
            "revokeAppPassword" -> { if (revokeFails) throw ApiException.Network(RuntimeException("offline")); revoked++; null }
            else -> error("not stubbed: ${method.name}")
        }
    } as EbookApi
}

class FakeApiClientFactory(val stub: ApiStub = ApiStub()) : ApiClientFactory {
    override suspend fun forAccount(accountId: String): EbookApi = stub.api
    override fun forCredentials(serverUrl: String, loginName: String, appPassword: String): EbookApi = stub.api
}

// ---- container --------------------------------------------------------------------------------------

class FakeContainer(
    context: Context,
    override val accountStore: FakeAccountStore = FakeAccountStore(listOf(account())),
    override val libraryRepository: FakeLibraryRepository = FakeLibraryRepository(),
    override val progressRepository: FakeProgressRepository = FakeProgressRepository(),
    override val downloadRepository: FakeDownloadRepository = FakeDownloadRepository(),
    override val editRepository: FakeEditRepository = FakeEditRepository(),
    override val settingsRepository: FakeSettingsRepository = FakeSettingsRepository(),
    override val syncEngine: FakeSyncEngine = FakeSyncEngine(),
    override val loginFlowClient: FakeLoginFlowClient = FakeLoginFlowClient(),
    override val apiClientFactory: FakeApiClientFactory = FakeApiClientFactory(),
) : AppContainer {
    override val appContext: Context = context.applicationContext
    override val json: Json = ApiJson
    override val httpClient: OkHttpClient get() = error("not used in UI tests")
    override val database: AppDatabase get() = error("not used in UI tests")
    override val downloadManager: DownloadManager get() = error("not used in UI tests")
}
