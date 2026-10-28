package com.somecatcode.ebookreader.ui.screens

import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.data.repo.HomeLogic
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.data.repo.ShelfKey
import com.somecatcode.ebookreader.ui.FakeAccountStore
import com.somecatcode.ebookreader.ui.FakeLibraryRepository
import com.somecatcode.ebookreader.ui.FakeSettingsRepository
import com.somecatcode.ebookreader.ui.FakeSyncEngine
import com.somecatcode.ebookreader.ui.account
import com.somecatcode.ebookreader.ui.book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val sync = FakeSyncEngine()

    private fun vm(
        books: List<LibraryBook>,
        settings: AppSettings = AppSettings(),
        accounts: FakeAccountStore = FakeAccountStore(listOf(account())),
        shelves: List<ShelfInfo> = emptyList(),
    ): HomeViewModel {
        val repo = FakeLibraryRepository(books).also { it.shelfList.value = shelves }
        return HomeViewModel(accounts, repo, FakeSettingsRepository(settings), sync, seed = 1L)
    }

    private fun HomeViewModel.awaitLoaded(): HomeUiState = runBlocking { withTimeout(10_000) { state.first { it.loaded } } }

    @Test
    fun withoutAccountsTheStateIsLoadedButHasNoAccounts() {
        val s = vm(emptyList(), accounts = FakeAccountStore()).awaitLoaded()
        assertFalse(s.hasAccounts)
        assertTrue(s.sections.isEmpty)
    }

    @Test
    fun sectionsComeFromTheLibraryAndHideEmptyOnes() {
        val s = vm(
            listOf(
                book(1, "Reading", status = ReadStatus.READING, genres = listOf("Fantasy")),
                book(2, "Match", genres = listOf("Fantasy")),
                book(3, "Plain"),
            ),
        ).awaitLoaded()
        assertTrue(s.hasAccounts)
        assertEquals(listOf(1L), s.sections.continueReading.map { it.key.fileId })
        assertTrue(s.sections.offline.isEmpty())
        assertEquals(listOf(2L), s.sections.suggestions.map { it.key.fileId })
        assertTrue(s.sections.shelves.isEmpty())
    }

    @Test
    fun shelvesAppearOnlyWhenSelectedInTheSettings() {
        val shelf = ShelfInfo(ShelfKey("acc1", 7), "Favourites", smart = false, count = 2, coverFileIds = emptyList())
        val books = listOf(book(1, "A"), book(2, "B"))
        assertTrue(vm(books, shelves = listOf(shelf)).awaitLoaded().sections.shelves.isEmpty())
        val selected = AppSettings(homeShelves = setOf(HomeLogic.shelfPref(shelf.key)))
        val s = vm(books, selected, shelves = listOf(shelf)).awaitLoaded()
        assertEquals(listOf("Favourites"), s.sections.shelves.map { it.shelf.name })
    }

    @Test
    fun refreshRequestsASyncOfTheShownAccount() {
        val vm = vm(listOf(book(1, "A")))
        vm.awaitLoaded()
        vm.refresh()
        assertEquals(listOf<String?>("acc1"), sync.requested)
    }
}
