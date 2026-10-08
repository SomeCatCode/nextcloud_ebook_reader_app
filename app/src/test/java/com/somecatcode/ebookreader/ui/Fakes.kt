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
import com.somecatcode.ebookreader.data.api.SmartQueryDto
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.DownloadEntity
import com.somecatcode.ebookreader.data.download.DownloadManager
import com.somecatcode.ebookreader.data.repo.AnnotationColor
import com.somecatcode.ebookreader.data.repo.AnnotationRepository
import com.somecatcode.ebookreader.data.repo.AnnotationType
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.data.repo.BookAnnotation
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.EditFailure
import com.somecatcode.ebookreader.data.repo.EditRepository
import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibraryFacets
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
import com.somecatcode.ebookreader.data.repo.ShelfKey
import com.somecatcode.ebookreader.data.repo.ShelfRepository
import com.somecatcode.ebookreader.data.repo.StoredProgress
import com.somecatcode.ebookreader.data.sync.SyncEngine
import com.somecatcode.ebookreader.data.sync.SyncOutcome
import com.somecatcode.ebookreader.data.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    path: String = "/Books/$title.$format",
    shared: Boolean = false,
    sharedOut: Boolean = false,
    owner: String? = null,
) = LibraryBook(
    key = BookKey(accountId, fileId), format = format, path = path, size = 1_000_000, title = title,
    authors = authors, series = series, seriesIndex = seriesIndex, descriptionHtml = null, language = "en", publisher = null,
    isbn = null, publishedAt = null, genres = genres, tags = tags, rating = rating, readStatus = status, hasCover = false,
    coverEtag = null, addedAt = fileId, editable = editable, downloadable = true, percentage = percentage, offline = offline,
    hasPendingEdit = pending, shared = shared, owner = owner, sharedOut = sharedOut,
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
                .filter { !filter.hideFinished || filter.status != null || it.readStatus != ReadStatus.FINISHED }
                .filter { b -> filter.include.all { t -> fakeTermMatches(t, b) } && filter.exclude.none { t -> fakeTermMatches(t, b) } }
                .filter { filter.series == null || it.series == filter.series }
                .filter { b -> filter.shared == null || com.somecatcode.ebookreader.data.repo.SharedFilters.matches(filter.shared!!, b.shared, b.sharedOut) }
                .filter { b -> filter.folder == null || com.somecatcode.ebookreader.data.repo.FolderTree.inFolder(b.path, filter.folder!!, filter.folderRecursive) }
                .sortedBy { it.title.lowercase() }
        }
    }
    override fun book(key: BookKey): Flow<LibraryBook?> = allBooks.map { list -> list.firstOrNull { it.key == key } }
    override fun shelves(accountIds: List<String>): Flow<List<ShelfInfo>> = shelfList
    override fun series(accountIds: List<String>): Flow<List<SeriesInfo>> = seriesList
    override fun genres(accountIds: List<String>): Flow<List<FacetCount>> = genreFacets
    override fun tags(accountIds: List<String>): Flow<List<FacetCount>> = tagFacets
    override fun continueReading(accountIds: List<String>, limit: Int): Flow<List<LibraryBook>> = MutableStateFlow(emptyList())
    override fun facets(accountIds: List<String>): Flow<LibraryFacets> =
        combine(genreFacets, tagFacets) { g, t -> LibraryFacets(genres = g, tags = t) }
}

/** genre/tag/format/author/series terms, enough for view model tests. */
private fun fakeTermMatches(t: String, b: LibraryBook): Boolean {
    val name = t.substringAfter(':')
    return when (t.substringBefore(':')) {
        "genre" -> b.genres.any { it.equals(name, ignoreCase = true) }
        "tag" -> b.tags.any { it.equals(name, ignoreCase = true) }
        "format" -> b.format.equals(name, ignoreCase = true)
        "author" -> b.authors.any { it.equals(name, ignoreCase = true) }
        "series" -> b.series.equals(name, ignoreCase = true)
        else -> false
    }
}

class FakeShelfRepository : ShelfRepository {
    val calls = mutableListOf<String>()
    var failWith: Exception? = null
    val membership = MutableStateFlow<Set<Long>>(emptySet())
    private fun record(call: String) {
        failWith?.let { throw it }
        calls += call
    }
    override suspend fun refresh(accountId: String) = record("refresh $accountId")
    override suspend fun refreshMembers(key: ShelfKey) = record("members ${key.shelfId}")
    override suspend fun create(accountId: String, name: String, query: SmartQueryDto?): ShelfKey {
        record("create $name ${query?.include.orEmpty()}")
        return ShelfKey(accountId, 99)
    }
    override suspend fun rename(key: ShelfKey, name: String) = record("rename ${key.shelfId} $name")
    override suspend fun updateQuery(key: ShelfKey, query: SmartQueryDto) = record("query ${key.shelfId} ${query.include}")
    override suspend fun delete(key: ShelfKey) = record("delete ${key.shelfId}")
    override suspend fun move(key: ShelfKey, delta: Int) = record("move ${key.shelfId} $delta")
    override suspend fun addBooks(key: ShelfKey, fileIds: List<Long>) = record("add ${key.shelfId} $fileIds")
    override suspend fun removeBooks(key: ShelfKey, fileIds: List<Long>) = record("remove ${key.shelfId} $fileIds")
    override fun shelvesOf(book: BookKey): Flow<Set<Long>> = membership
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

class FakeAnnotationRepository(initial: List<BookAnnotation> = emptyList()) : AnnotationRepository {
    val state = MutableStateFlow(initial)
    var refreshed = 0
    private var next = 0
    override fun annotations(key: BookKey): Flow<List<BookAnnotation>> = state.map { list -> list.filter { it.key == key } }
    override suspend fun create(key: BookKey, locator: Locator, text: String?, color: AnnotationColor, note: String?): String {
        val uuid = "00000000-0000-4000-8000-%012d".format(++next)
        val type = if (note.isNullOrBlank()) AnnotationType.HIGHLIGHT else AnnotationType.NOTE
        state.value = state.value + BookAnnotation(key, uuid, type, locator, text, note, color, next.toLong(), next.toLong(), true)
        return uuid
    }
    override suspend fun setNote(key: BookKey, uuid: String, note: String?) = change(uuid) { it.copy(note = note?.takeIf { n -> n.isNotBlank() }) }
    override suspend fun setColor(key: BookKey, uuid: String, color: AnnotationColor) = change(uuid) { it.copy(color = color) }
    override suspend fun delete(key: BookKey, uuid: String) {
        state.value = state.value.filterNot { it.uuid == uuid }
    }
    override suspend fun refresh(key: BookKey) { refreshed++ }
    override suspend fun pushDirty(accountId: String) = Unit
    private fun change(uuid: String, f: (BookAnnotation) -> BookAnnotation) {
        state.value = state.value.map { if (it.uuid == uuid) f(it) else it }
    }
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
    override val shelfRepository: FakeShelfRepository = FakeShelfRepository(),
    override val annotationRepository: FakeAnnotationRepository = FakeAnnotationRepository(),
) : AppContainer {
    override val appContext: Context = context.applicationContext
    override val json: Json = ApiJson
    override val httpClient: OkHttpClient get() = error("not used in UI tests")
    override val database: AppDatabase get() = error("not used in UI tests")
    override val downloadManager: DownloadManager get() = error("not used in UI tests")
}
