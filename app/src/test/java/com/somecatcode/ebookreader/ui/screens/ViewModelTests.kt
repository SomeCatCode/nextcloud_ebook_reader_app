package com.somecatcode.ebookreader.ui.screens

import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.Locator
import com.somecatcode.ebookreader.data.api.Locations
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.api.ServerCompatibility
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.LibrarySort
import com.somecatcode.ebookreader.data.repo.OfflineState
import com.somecatcode.ebookreader.data.repo.ProgressConflict
import com.somecatcode.ebookreader.data.sync.SyncError
import com.somecatcode.ebookreader.data.sync.SyncState
import com.somecatcode.ebookreader.reader.BookSource
import com.somecatcode.ebookreader.reader.ReaderToHost
import com.somecatcode.ebookreader.ui.FakeAccountStore
import com.somecatcode.ebookreader.ui.FakeApiClientFactory
import com.somecatcode.ebookreader.ui.FakeDownloadRepository
import com.somecatcode.ebookreader.ui.FakeEditRepository
import com.somecatcode.ebookreader.ui.FakeLibraryRepository
import com.somecatcode.ebookreader.ui.FakeLoginFlowClient
import com.somecatcode.ebookreader.ui.FakeProgressRepository
import com.somecatcode.ebookreader.ui.FakeSettingsRepository
import com.somecatcode.ebookreader.ui.FakeSyncEngine
import com.somecatcode.ebookreader.ui.account
import com.somecatcode.ebookreader.ui.book
import com.somecatcode.ebookreader.ui.util.normalizeServerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTests {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- server input -----------------------------------------------------------------------------

    @Test
    fun normalizeServerInput_addsHttpsAndRejectsHttpAndGarbage() {
        assertEquals("https://cloud.example.org", normalizeServerInput(" cloud.example.org/ "))
        assertEquals("https://cloud.example.org", normalizeServerInput("https://cloud.example.org/index.php"))
        assertEquals("https://example.org/nextcloud", normalizeServerInput("example.org/nextcloud/"))
        assertEquals("https://192.168.1.5:8443", normalizeServerInput("192.168.1.5:8443"))
        assertNull(normalizeServerInput(""))
        assertNull(normalizeServerInput("http://cloud.example.org"))
        assertNull(normalizeServerInput("not a url"))
        assertNull(normalizeServerInput("ftp://cloud.example.org"))
    }

    // ---- accounts ---------------------------------------------------------------------------------

    private fun accountsVm(
        store: FakeAccountStore = FakeAccountStore(),
        login: FakeLoginFlowClient = FakeLoginFlowClient(),
        factory: FakeApiClientFactory = FakeApiClientFactory(),
        sync: FakeSyncEngine = FakeSyncEngine(),
    ) = AccountsViewModel(store, login, factory, sync, pollIntervalMs = 10, pollTimeoutMs = 100)

    @Test
    fun addAccount_invalidUrlShowsErrorWithoutStartingLoginFlow() = runTest(dispatcher) {
        val login = FakeLoginFlowClient()
        val vm = accountsVm(login = login)
        backgroundScope.launch { vm.state.collect {} }
        vm.showAddAccount()
        vm.onServerInputChange("http://plain.example.org")
        vm.startLogin()
        assertEquals(AddError.INVALID_URL, vm.state.value.addFlow?.error)
        assertEquals(AddStage.INPUT, vm.state.value.addFlow?.stage)
        assertTrue(login.started.isEmpty())
    }

    @Test
    fun addAccount_successStoresAccountOpensBrowserAndSyncs() = runTest(dispatcher) {
        val store = FakeAccountStore()
        val sync = FakeSyncEngine()
        val vm = accountsVm(store = store, sync = sync)
        backgroundScope.launch { vm.state.collect {} }
        vm.showAddAccount()
        vm.onServerInputChange("cloud.example.org")
        vm.startLogin()
        advanceUntilIdle()
        assertEquals("https://cloud.example.org", store.added?.serverUrl)
        assertEquals("app-password", store.added?.appPassword)
        assertNull(vm.state.value.addFlow)
        assertEquals(listOf<String?>("new"), sync.requested)
        val first = vm.events.first()
        assertEquals(AccountsEvent.OpenBrowser("https://cloud.example.org/login/flow"), first)
    }

    @Test
    fun addAccount_cancelWhileWaitingReturnsToInput() = runTest(dispatcher) {
        val login = FakeLoginFlowClient().apply { result = null }
        val vm = accountsVm(login = login)
        backgroundScope.launch { vm.state.collect {} }
        vm.showAddAccount()
        vm.onServerInputChange("cloud.example.org")
        vm.startLogin()
        assertEquals(AddStage.WAITING, vm.state.value.addFlow?.stage)
        vm.cancelLogin()
        advanceUntilIdle()
        assertEquals(AddStage.INPUT, vm.state.value.addFlow?.stage)
    }

    @Test
    fun addAccount_timeoutShowsError() = runTest(dispatcher) {
        val login = FakeLoginFlowClient().apply { result = null }
        val vm = accountsVm(login = login)
        backgroundScope.launch { vm.state.collect {} }
        vm.showAddAccount()
        vm.onServerInputChange("cloud.example.org")
        vm.startLogin()
        advanceUntilIdle()
        assertEquals(AddError.TIMEOUT, vm.state.value.addFlow?.error)
    }

    @Test
    fun addAccount_appMissingRevokesPasswordAndStoresNothing() = runTest(dispatcher) {
        val factory = FakeApiClientFactory()
        factory.stub.compatibility = ServerCompatibility.AppMissing
        val store = FakeAccountStore()
        val vm = accountsVm(store = store, factory = factory)
        backgroundScope.launch { vm.state.collect {} }
        vm.showAddAccount()
        vm.onServerInputChange("cloud.example.org")
        vm.startLogin()
        advanceUntilIdle()
        assertEquals(AddError.APP_MISSING, vm.state.value.addFlow?.error)
        assertEquals(1, factory.stub.revoked)
        assertNull(store.added)
    }

    @Test
    fun addAccount_tooOldAndDuplicateAreReported() = runTest(dispatcher) {
        val factory = FakeApiClientFactory()
        factory.stub.compatibility = ServerCompatibility.AppTooOld
        val vm = accountsVm(factory = factory)
        backgroundScope.launch { vm.state.collect {} }
        vm.showAddAccount()
        vm.onServerInputChange("cloud.example.org")
        vm.startLogin()
        advanceUntilIdle()
        assertEquals(AddError.APP_TOO_OLD, vm.state.value.addFlow?.error)

        val dup = FakeApiClientFactory()
        val store = FakeAccountStore(listOf(account(server = "https://cloud.example.org").copy(userId = "alice")))
        val vm2 = accountsVm(store = store, factory = dup)
        backgroundScope.launch { vm2.state.collect {} }
        vm2.showAddAccount()
        vm2.onServerInputChange("cloud.example.org")
        vm2.startLogin()
        advanceUntilIdle()
        assertEquals(AddError.DUPLICATE, vm2.state.value.addFlow?.error)
        assertEquals(1, dup.stub.revoked)
    }

    @Test
    fun relogin_updatesCredentialsOfSameUser() = runTest(dispatcher) {
        val store = FakeAccountStore(listOf(account("acc1").copy(userId = "alice")))
        val vm = accountsVm(store = store)
        backgroundScope.launch { vm.state.collect {} }
        vm.showRelogin(store.state.value.first())
        vm.startLogin()
        advanceUntilIdle()
        assertEquals("acc1", store.updatedCredentials?.first)
        assertNull(vm.state.value.addFlow)
    }

    @Test
    fun removeAccount_revokeFailureStillRemovesAndShowsHint() = runTest(dispatcher) {
        val factory = FakeApiClientFactory().apply { stub.revokeFails = true }
        val store = FakeAccountStore(listOf(account("acc1")))
        val vm = accountsVm(store = store, factory = factory)
        backgroundScope.launch { vm.state.collect {} }
        vm.askRemove(store.state.value.first())
        vm.confirmRemove()
        advanceUntilIdle()
        assertEquals(listOf("acc1"), store.removed)
        assertTrue(vm.state.value.revokeFailedHint)
        assertNull(vm.state.value.removeCandidate)
    }

    @Test
    fun accountStatus_mapsSyncStateAndMissingCredentials() = runTest(dispatcher) {
        val store = FakeAccountStore(listOf(account("a"), account("b"), account("c"), account("d")), missingCredentials = setOf("d"))
        val sync = FakeSyncEngine()
        sync.syncState.value = mapOf(
            "a" to SyncState.Failed(SyncError.UNAUTHORIZED, 0),
            "b" to SyncState.Failed(SyncError.APP_UNAVAILABLE, 0),
            "c" to SyncState.Failed(SyncError.OFFLINE, 0),
        )
        val vm = accountsVm(store = store, sync = sync)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        val status = vm.state.value.rows.associate { it.account.id to it.status }
        assertEquals(AccountStatus.AUTH_EXPIRED, status["a"])
        assertEquals(AccountStatus.APP_UNAVAILABLE, status["b"])
        assertEquals(AccountStatus.OFFLINE, status["c"])
        assertEquals(AccountStatus.AUTH_EXPIRED, status["d"])
    }

    // ---- library ----------------------------------------------------------------------------------

    private fun libraryVm(
        library: FakeLibraryRepository,
        store: FakeAccountStore = FakeAccountStore(listOf(account("acc1"), account("acc2", "Bob"))),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        sync: FakeSyncEngine = FakeSyncEngine(),
    ) = LibraryViewModel(store, library, settings, sync)

    @Test
    fun library_filtersAreReflectedInStateAndQuery() = runTest(dispatcher) {
        val library = FakeLibraryRepository(
            listOf(
                book(1, "Dune", genres = listOf("Sci-Fi"), status = ReadStatus.READING),
                book(2, "Emma", format = "mobi", offline = OfflineState(DownloadState.DONE)),
                book(3, "Hobbit", genres = listOf("Fantasy")),
            ),
        )
        val vm = libraryVm(library)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("Dune", "Emma", "Hobbit"), vm.state.value.books.map { it.title })

        vm.setOnlyOffline(true)
        assertEquals(listOf("Emma"), vm.state.value.books.map { it.title })
        vm.setOnlyOffline(false)
        vm.setStatus(ReadStatus.READING)
        assertEquals(listOf("Dune"), vm.state.value.books.map { it.title })
        vm.setStatus(null)
        vm.setGenres(setOf("Fantasy"))
        assertEquals(listOf("Hobbit"), vm.state.value.books.map { it.title })
        vm.setGenres(emptySet())
        vm.setFormats(setOf("mobi"))
        assertEquals(listOf("Emma"), vm.state.value.books.map { it.title })
        assertEquals(1, vm.state.value.query.activeFilterCount)
        vm.setSearch("hob")
        assertEquals("hob", library.lastFilter.value.search)
        vm.clearFilters()
        assertFalse(vm.state.value.query.hasActiveFilter)
        assertEquals(3, vm.state.value.books.size)
        vm.setSort(LibrarySort.RATING)
        assertEquals(LibrarySort.RATING, library.lastFilter.value.sort)
    }

    @Test
    fun library_singleAccountVersusMergedAndRefresh() = runTest(dispatcher) {
        val library = FakeLibraryRepository(listOf(book(1, "A", accountId = "acc1"), book(2, "B", accountId = "acc2")))
        val settings = FakeSettingsRepository()
        val sync = FakeSyncEngine()
        val vm = libraryVm(library, settings = settings, sync = sync)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("A"), vm.state.value.books.map { it.title })
        vm.selectAccount(null)
        assertEquals(listOf("A", "B"), vm.state.value.books.map { it.title })
        assertTrue(vm.state.value.merged)
        vm.selectAccount("acc2")
        assertEquals(listOf("B"), vm.state.value.books.map { it.title })
        vm.refresh()
        assertEquals(listOf<String?>("acc2"), sync.requested)
    }

    @Test
    fun library_bannerAndRefreshingFollowSyncState() = runTest(dispatcher) {
        val sync = FakeSyncEngine()
        val vm = libraryVm(FakeLibraryRepository(), store = FakeAccountStore(listOf(account("acc1"))), sync = sync)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertNull(vm.state.value.banner)
        sync.syncState.value = mapOf("acc1" to SyncState.Failed(SyncError.UNAUTHORIZED, 0))
        assertEquals(LibraryBanner.AuthExpired("acc1"), vm.state.value.banner)
        sync.syncState.value = mapOf("acc1" to SyncState.Failed(SyncError.OFFLINE, 0))
        assertEquals(LibraryBanner.Offline, vm.state.value.banner)
        sync.syncState.value = mapOf("acc1" to SyncState.Running(com.somecatcode.ebookreader.data.sync.SyncPhase.BOOKS))
        assertTrue(vm.state.value.refreshing)
    }

    // ---- book detail / editing --------------------------------------------------------------------

    @Test
    fun metadataPatch_containsOnlyChangedFieldsAndNullsClearedOnes() {
        val original = book(1, "Dune", authors = listOf("Frank Herbert"), series = "Dune", seriesIndex = 1.0, genres = listOf("Sci-Fi"))
        val unchanged = BookEditForm.from(original)
        assertNull(buildMetadataPatch(original, unchanged))

        val patch = buildMetadataPatch(
            original,
            unchanged.copy(title = " Dune Messiah ", authors = "Frank Herbert, Brian Herbert", series = "", seriesIndex = "", genres = "Sci-Fi, Classic"),
        )!!.fields
        assertEquals(JsonPrimitive("Dune Messiah"), patch["title"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Frank Herbert"), JsonPrimitive("Brian Herbert"))), patch["authors"])
        assertEquals(JsonNull, patch["series"])
        assertEquals(JsonNull, patch["seriesIndex"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Sci-Fi"), JsonPrimitive("Classic"))), patch["genres"])
        assertFalse(patch.containsKey("tags"))
    }

    @Test
    fun editValidationAndListHelpers() {
        val form = BookEditForm("", "", "", "", "", "")
        assertEquals(EditError.TITLE_EMPTY, validateEdit(form))
        assertEquals(EditError.INDEX_INVALID, validateEdit(form.copy(title = "x", seriesIndex = "abc")))
        assertNull(validateEdit(form.copy(title = "x", seriesIndex = "2,5")))
        assertEquals(2.5, parseIndex("2,5")!!, 0.0)
        assertEquals("Sci-Fi, Fantasy", appendToList("Sci-Fi", "Fantasy"))
        assertEquals("Sci-Fi, Fantasy", appendToList("Sci-Fi, fan", "Fantasy"))
        assertEquals("Sci-Fi", appendToList("Sci-Fi", "sci-fi"))
    }

    @Test
    fun bookDetail_savesEditsRatingAndStatusThroughEditRepository() = runTest(dispatcher) {
        val library = FakeLibraryRepository(listOf(book(1, "Dune", rating = 3)))
        val edits = FakeEditRepository()
        val vm = BookDetailViewModel(BookKey("acc1", 1), library, edits, FakeDownloadRepository())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.startEdit()
        vm.updateForm { it.copy(title = "") }
        vm.saveEdit()
        assertEquals(EditError.TITLE_EMPTY, vm.state.value.editError)
        assertTrue(edits.metadataEdits.isEmpty())

        vm.updateForm { it.copy(title = "Dune (new)") }
        vm.saveEdit()
        assertEquals(JsonPrimitive("Dune (new)"), edits.metadataEdits.single().second.fields["title"])
        assertNull(vm.state.value.editing)

        vm.setRating(5)
        vm.setRating(null)
        vm.setStatus(ReadStatus.FINISHED)
        assertEquals(
            listOf(AppDataPatch(setRating = true, rating = 5), AppDataPatch(setRating = true, rating = null), AppDataPatch(readStatus = ReadStatus.FINISHED)),
            edits.appDataEdits.map { it.second },
        )
    }

    @Test
    fun bookDetail_notEditableCannotStartEdit() = runTest(dispatcher) {
        val library = FakeLibraryRepository(listOf(book(1, "Dune", editable = false)))
        val vm = BookDetailViewModel(BookKey("acc1", 1), library, FakeEditRepository(), FakeDownloadRepository())
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.startEdit()
        assertNull(vm.state.value.editing)
    }

    // ---- reader -----------------------------------------------------------------------------------

    private val localLocator = Locator("c1.xhtml", locations = Locations(totalProgression = 0.2))
    private val remoteLocator = Locator("c5.xhtml", locations = Locations(totalProgression = 0.6))
    private val key = BookKey("acc1", 1)

    private fun conflict() = ProgressConflict(key, localLocator, 0.2, remoteLocator, 0.6, "Pixel", 1000)

    private fun readerVm(progress: FakeProgressRepository, downloads: FakeDownloadRepository = FakeDownloadRepository(), debounce: Long = 1000) =
        ReaderViewModel(
            key, FakeLibraryRepository(listOf(book(1, "Dune"))), progress, downloads, FakeSettingsRepository(),
            remoteCheckTimeoutMs = 100, saveDebounceMs = debounce,
        )

    @Test
    fun reader_conflictAcceptJumpsToRemotePosition() = runTest(dispatcher) {
        val progress = FakeProgressRepository(conflict = conflict())
        val vm = readerVm(progress)
        advanceUntilIdle()
        assertEquals(ReaderPhase.CONFLICT, vm.state.value.phase)
        assertNull(vm.state.value.openMessage)
        vm.resolveConflict(acceptRemote = true)
        advanceUntilIdle()
        assertNotNull(progress.accepted)
        assertEquals(remoteLocator, vm.state.value.openMessage?.initialLocator)
        assertEquals(ReaderPhase.OPENING, vm.state.value.phase)
    }

    @Test
    fun reader_conflictKeepLocalReUploadsAndOpensAtLocalPosition() = runTest(dispatcher) {
        val progress = FakeProgressRepository(conflict = conflict())
        val vm = readerVm(progress)
        advanceUntilIdle()
        vm.resolveConflict(acceptRemote = false)
        advanceUntilIdle()
        assertNotNull(progress.kept)
        assertEquals(localLocator, vm.state.value.openMessage?.initialLocator)
    }

    @Test
    fun reader_withoutConflictOpensAtStoredProgressAndUsesOfflineFile() = runTest(dispatcher) {
        val stored = com.somecatcode.ebookreader.data.repo.StoredProgress(key, localLocator, 0.2, "me", 1, false)
        val progress = FakeProgressRepository(local = stored)
        val vm = readerVm(progress, FakeDownloadRepository(localFile = java.io.File("x.epub")))
        advanceUntilIdle()
        val open = vm.state.value.openMessage!!
        assertEquals(localLocator, open.initialLocator)
        assertTrue(open.source is BookSource.File)
        assertEquals("epub", open.book.format)
    }

    @Test
    fun reader_relocateIsDebouncedAndFlushedOnLeave() = runTest(dispatcher) {
        val progress = FakeProgressRepository()
        val vm = readerVm(progress, debounce = 1000)
        advanceUntilIdle()
        vm.onEvent(ReaderToHost.Relocate(localLocator, 0.1))
        vm.onEvent(ReaderToHost.Relocate(remoteLocator, 0.5))
        advanceTimeBy(500)
        assertTrue(progress.saved.isEmpty())
        advanceTimeBy(600)
        assertEquals(1, progress.saved.size)
        assertEquals(0.5, progress.saved.single().third, 0.0)

        vm.onEvent(ReaderToHost.Relocate(localLocator, 0.7))
        vm.flushProgress()
        advanceUntilIdle()
        assertEquals(2, progress.saved.size)
        assertEquals(0.7, progress.saved.last().third, 0.0)
    }

    @Test
    fun reader_tapCenterTogglesBarsAndExternalLinksAreFiltered() = runTest(dispatcher) {
        val vm = readerVm(FakeProgressRepository())
        advanceUntilIdle()
        vm.onEvent(ReaderToHost.Tap("left"))
        assertFalse(vm.state.value.barsVisible)
        vm.onEvent(ReaderToHost.Tap("center"))
        assertTrue(vm.state.value.barsVisible)
        vm.onEvent(ReaderToHost.ExternalLink("javascript:alert(1)"))
        assertNull(vm.state.value.externalLink)
        vm.onEvent(ReaderToHost.ExternalLink("https://example.org"))
        assertEquals("https://example.org", vm.state.value.externalLink)
    }

    @Test
    fun bookSource_dependsOnFormatAndOfflineState() {
        assertTrue(buildBookSource("epub", "a.epub", offline = true) is BookSource.File)
        assertTrue(buildBookSource("epub", "a.epub", offline = false) is BookSource.RemoteZip)
        assertTrue(buildBookSource("cbz", "a.cbz", offline = false) is BookSource.RemoteComic)
        assertTrue(buildBookSource("mobi", "a.mobi", offline = false) is BookSource.File)
        assertTrue(isSafeExternalUrl("mailto:a@b.c"))
        assertFalse(isSafeExternalUrl("file:///etc/passwd"))
    }
}
