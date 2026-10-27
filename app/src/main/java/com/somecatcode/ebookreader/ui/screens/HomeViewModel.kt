package com.somecatcode.ebookreader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.repo.HomeLogic
import com.somecatcode.ebookreader.data.repo.HomeSections
import com.somecatcode.ebookreader.data.repo.HomeShelf
import com.somecatcode.ebookreader.data.repo.LibraryFilter
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.LibrarySort
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.data.sync.SyncEngine
import com.somecatcode.ebookreader.data.sync.SyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random

data class HomeUiState(
    /** false until the accounts and the first sections are loaded (no empty state flash). */
    val loaded: Boolean = false,
    val hasAccounts: Boolean = false,
    val sections: HomeSections = HomeSections(),
    val refreshing: Boolean = false,
)

/**
 * Home screen: continue reading, synced offline, suggestions and random shelves of the shown accounts
 * (all accounts when the library is merged, otherwise the selected one, like the library).
 * Everything is computed from Room data off the main thread; shelves are picked once per [shelfSeed]
 * (new seed on a new start and on pull-to-refresh), so the rows stay stable while the screen is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    accountStore: AccountStore,
    private val library: LibraryRepository,
    settings: SettingsRepository,
    private val sync: SyncEngine,
    seed: Long = Random.nextLong(),
) : ViewModel() {

    private val shelfSeed = MutableStateFlow(seed)

    private val accounts = accountStore.accounts

    private val shownIds: Flow<List<String>> = combine(accounts, settings.settings) { list, s ->
        when {
            list.isEmpty() -> emptyList()
            s.mergedLibrary -> list.map { it.id }
            else -> listOf((list.firstOrNull { it.id == s.lastAccountId } ?: list.first()).id)
        }
    }.distinctUntilChanged()

    private val selectedShelves = settings.settings.map { it.homeShelves }.distinctUntilChanged()

    /** Whole library, most recently read first (books without progress last). */
    private val allBooks = shownIds.flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyList())
        else library.books(ids, LibraryFilter(sort = LibrarySort.RECENTLY_READ, descending = true))
    }

    private val shelves: Flow<List<HomeShelf>> = combine(shownIds, selectedShelves, shelfSeed) { ids, selected, s -> Triple(ids, selected, s) }
        .flatMapLatest { (ids, selected, s) ->
            if (ids.isEmpty() || selected.isEmpty()) {
                flowOf(emptyList())
            } else {
                library.shelves(ids).map { HomeLogic.pickShelves(it, selected, Random(s)) }.distinctUntilChanged().flatMapLatest { picked ->
                    if (picked.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            picked.map { shelf ->
                                library.books(listOf(shelf.key.accountId), LibraryFilter(shelf = shelf.key, sort = LibrarySort.SHELF))
                                    .map { HomeShelf(shelf, it.take(HomeLogic.ROW_LIMIT)) }
                            },
                        ) { it.toList() }
                    }
                }
            }
        }

    private val sections: Flow<HomeSections> = combine(allBooks, shelves) { books, sh -> HomeLogic.build(books, sh) }
        .flowOn(Dispatchers.Default)

    private val refreshing: Flow<Boolean> = combine(shownIds, sync.state) { ids, states ->
        ids.any { states[it] is SyncState.Running }
    }

    val state: StateFlow<HomeUiState> = combine(accounts, sections, refreshing) { list, s, r ->
        HomeUiState(loaded = true, hasAccounts = list.isNotEmpty(), sections = s, refreshing = r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val shown: StateFlow<List<String>> = shownIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Pull-to-refresh: sync the shown accounts and pick new shelves. */
    fun refresh() {
        shelfSeed.value = Random.nextLong()
        val ids = shown.value
        if (ids.isEmpty()) sync.requestSync(null) else ids.forEach { sync.requestSync(it) }
    }
}
