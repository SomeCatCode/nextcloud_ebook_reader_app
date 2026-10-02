package com.somecatcode.ebookreader.data

import android.content.Context
import androidx.room.Room
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.account.Credentials
import com.somecatcode.ebookreader.data.account.NewAccount
import com.somecatcode.ebookreader.data.account.Account
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.EbookApi
import com.somecatcode.ebookreader.data.api.EbookApiImpl
import com.somecatcode.ebookreader.data.db.AccountEntity
import com.somecatcode.ebookreader.data.db.AppDatabase
import com.somecatcode.ebookreader.data.db.PinnedBy
import com.somecatcode.ebookreader.data.download.DownloadManager
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.data.sync.LocalChangesScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Wraps `data` into an OCS v2 envelope answer. */
fun ocs(data: String, code: Int = 200): MockResponse = MockResponse()
    .setResponseCode(code)
    .addHeader("Content-Type", "application/json")
    .setBody("""{"ocs":{"meta":{"status":"ok","statuscode":$code,"message":"OK"},"data":$data}}""")

fun ocsError(code: Int, message: String): MockResponse = MockResponse()
    .setResponseCode(code)
    .addHeader("Content-Type", "application/json")
    .setBody("""{"ocs":{"meta":{"status":"failure","statuscode":$code,"message":"$message"},"data":[]}}""")

fun bookJson(
    fileId: Long,
    title: String = "Book $fileId",
    mtime: Long = 1,
    size: Long = 1000,
    series: String? = null,
    seriesIndex: Double? = null,
    genres: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    updatedAt: Long = 1,
    downloadable: Boolean = true,
): String {
    val seriesPart = if (series != null) ""","series":"$series","seriesIndex":${seriesIndex ?: 1.0}""" else ""
    return """{"fileId":$fileId,"format":"epub","path":"/Books/$fileId.epub","size":$size,"title":"$title","authors":["Author"]$seriesPart,
        "genres":[${genres.joinToString(",") { "\"$it\"" }}],"tags":[${tags.joinToString(",") { "\"$it\"" }}],
        "readStatus":"unread","hasCover":false,"mtime":$mtime,"addedAt":5,"updatedAt":$updatedAt,"editable":true,
        "downloadable":$downloadable,"overrides":[],"hasSidecar":false}"""
}

fun progressJson(fileId: Long, href: String = "c1.xhtml", pct: Double = 0.5, clientUpdatedAt: Long = 100, updatedAt: Long = 101, device: String = "Web"): String =
    """{"fileId":$fileId,"locator":{"href":"$href","locations":{"progression":$pct,"totalProgression":$pct}},"percentage":$pct,"device":"$device","clientUpdatedAt":$clientUpdatedAt,"updatedAt":$updatedAt}"""

/** Dispatcher answering by first matching route; unknown paths are 404. Records every request. */
class RoutingDispatcher : Dispatcher() {
    private class Route(val match: (RecordedRequest) -> Boolean, val answer: (RecordedRequest) -> MockResponse)

    private val routes = CopyOnWriteArrayList<Route>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    fun on(method: String, pathContains: String, answer: (RecordedRequest) -> MockResponse) {
        routes.add(0, Route({ it.method == method && (it.path ?: "").contains(pathContains) }, answer))
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        return routes.firstOrNull { it.match(request) }?.answer?.invoke(request) ?: MockResponse().setResponseCode(404).setBody("{}")
    }
}

fun serverWith(dispatcher: Dispatcher): MockWebServer = MockWebServer().apply {
    this.dispatcher = dispatcher
    start()
}

fun MockWebServer.ebookApi(client: OkHttpClient = OkHttpClient()): EbookApiImpl =
    EbookApiImpl(url("/").toString(), "alice", "secret-app-pw", client)

/** Always hands out the same API (pointing at a MockWebServer). */
class StaticApiFactory(private val api: EbookApi) : ApiClientFactory {
    override suspend fun forAccount(accountId: String): EbookApi = api
    override fun forCredentials(serverUrl: String, loginName: String, appPassword: String): EbookApi = api
}

class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

class RecordingScheduler : LocalChangesScheduler {
    val scheduled = mutableListOf<String>()
    override fun schedule(accountId: String) {
        scheduled += accountId
    }
}

class RecordingDownloadManager : DownloadManager {
    val enqueued = mutableListOf<Triple<BookKey, PinnedBy, String?>>()
    val deleted = mutableListOf<BookKey>()
    var resumed = 0
    override suspend fun enqueue(key: BookKey, pinnedBy: PinnedBy, pinRef: String?) {
        enqueued += Triple(key, pinnedBy, pinRef)
    }
    override suspend fun cancel(key: BookKey) {}
    override suspend fun delete(key: BookKey) {
        deleted += key
    }
    override suspend fun localFile(key: BookKey): File? = null
    override suspend fun resumePending() {
        resumed++
    }
    override fun freeBytes(): Long = Long.MAX_VALUE
}

/** In-memory credentials, for classes that need an [AccountStore] but not the real one. */
class FakeAccountStore(private val accountsList: List<Account> = emptyList()) : AccountStore {
    override val accounts: Flow<List<Account>> = emptyFlow()
    override suspend fun list() = accountsList
    override suspend fun get(accountId: String) = accountsList.firstOrNull { it.id == accountId }
    override suspend fun add(account: NewAccount): Account = error("not needed")
    override suspend fun updateCredentials(accountId: String, credentials: Credentials) {}
    override suspend fun credentials(accountId: String) = Credentials("alice", "secret-app-pw")
    override suspend fun remove(accountId: String) {}
}

fun newDatabase(context: Context): AppDatabase =
    Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

suspend fun AppDatabase.addAccount(id: String = "acc1", userId: String = "alice", cursor: String? = null): AccountEntity {
    val entity = AccountEntity(id, "https://cloud.example.org", "alice", userId, "Alice", "31.0", null, cursor, null, 1)
    accountDao().upsert(entity)
    return entity
}
