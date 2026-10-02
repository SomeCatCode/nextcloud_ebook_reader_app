package com.somecatcode.ebookreader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.data.account.Account
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibraryFilter
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.LibrarySort
import com.somecatcode.ebookreader.data.repo.SeriesInfo
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.data.sync.SyncEngine
import com.somecatcode.ebookreader.data.sync.SyncError
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryTab { BOOKS, SHELVES, SERIES }

/** Formats offered by the server (docs/CONTRACTS.md); used for the format filter. */
val KnownFormats = listOf("epub", "mobi", "azw3", "fb2", "fbz", "cbz", "cbr", "cb7", "cbt")

sealed interface LibraryBanner {
    data object Offline : LibraryBanner
    data class AuthExpired(val accountId: String) : LibraryBanner
    data object AppUnavailable : LibraryBanner
    data object ServerError : LibraryBanner
}

/** What the user selected; separate from the data so the query flows can react to it. */
data class LibraryQuery(
    val tab: LibraryTab = LibraryTab.BOOKS,
    val searchOpen: Boolean = false,
    val filter: LibraryFilter = LibraryFilter(),
    val formats: Set<String> = emptySet(),
) {
    val activeFilterCount: Int
        get() = listOf(
            filter.status != null,
            filter.genres.isNotEmpty(),
            filter.tags.isNotEmpty(),
            formats.isNotEmpty(),
            filter.onlyOffline,
        ).count { it }
    val hasActiveFilter: Boolean get() = activeFilterCount > 0 || !filter.search.isNullOrBlank()
}

data class LibraryUiState(
    val accountsLoaded: Boolean = false,
    val accounts: List<Account> = emptyList(),
    /** Accounts whose library is shown. */
    val shownAccountIds: List<String> = emptyList(),
    val merged: Boolean = false,
    val grid: Boolean = true,
    val query: LibraryQuery = LibraryQuery(),
    val books: List<LibraryBook> = emptyList(),
    val continueReading: List<LibraryBook> = emptyList(),
    val shelves: List<ShelfInfo> = emptyList(),
    val series: List<SeriesInfo> = emptyList(),
    val genres: List<FacetCount> = emptyList(),
    val tags: List<FacetCount> = emptyList(),
    val refreshing: Boolean = false,
    val banner: LibraryBanner? = null,
) {
    val accountNames: Map<String, String> get() = accounts.associate { it.id to (it.displayName?.takeIf(String::isNotBlank) ?: it.loginName) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    accountStore: AccountStore,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val sync: SyncEngine,
) : ViewModel() {

    private val query = MutableStateFlow(LibraryQuery())

    private val accounts = accountStore.accounts

    /** Single account (selected, default first) or all accounts when merged. */
    private val shownIds: Flow<List<String>> = combine(accounts, settings.settings) { list, s ->
        when {
            list.isEmpty() -> emptyList()
            s.mergedLibrary -> list.map { it.id }
            else -> listOf((list.firstOrNull { it.id == s.lastAccountId } ?: list.first()).id)
        }
    }.distinctUntilChanged()

    private val books: Flow<List<LibraryBook>> = combine(shownIds, query) { ids, q -> ids to q }
        .flatMapLatest { (ids, q) ->
            if (ids.isEmpty()) flowOf(emptyList())
            else library.books(ids, q.filter).map { list ->
                if (q.formats.isEmpty()) list else list.filter { it.format.lowercase() in q.formats }
            }
        }

    private val facets: Flow<Triple<List<ShelfInfo>, List<SeriesInfo>, Pair<List<FacetCount>, List<FacetCount>>>> =
        shownIds.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(Triple(emptyList(), emptyList(), emptyList<FacetCount>() to emptyList()))
            else combine(library.shelves(ids), library.series(ids), library.genres(ids), library.tags(ids)) { sh, se, g, t ->
                Triple(sh, se, g to t)
            }
        }

    private val continueReading: Flow<List<LibraryBook>> =
        shownIds.flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyList()) else library.continueReading(ids, 10) }

    private val syncInfo: Flow<Pair<Boolean, LibraryBanner?>> = combine(shownIds, sync.state) { ids, states ->
        val relevant = ids.mapNotNull { id -> states[id]?.let { id to it } }
        val refreshing = relevant.any { it.second is SyncState.Running }
        val failed = relevant.mapNotNull { (id, st) -> (st as? SyncState.Failed)?.let { id to it.error } }
        val banner = failed.firstOrNull { it.second == SyncError.UNAUTHORIZED }?.let { LibraryBanner.AuthExpired(it.first) }
            ?: failed.firstOrNull { it.second == SyncError.APP_UNAVAILABLE }?.let { LibraryBanner.AppUnavailable }
            ?: failed.firstOrNull { it.second == SyncError.SERVER || it.second == SyncError.UNKNOWN }?.let { LibraryBanner.ServerError }
            ?: failed.firstOrNull { it.second == SyncError.OFFLINE }?.let { LibraryBanner.Offline }
        refreshing to banner
    }

    val state: StateFlow<LibraryUiState> = combine(
        accounts, settings.settings, shownIds, query,
    ) { list, s, ids, q -> LibraryUiState(accountsLoaded = true, accounts = list, shownAccountIds = ids, merged = s.mergedLibrary, grid = s.libraryLayout != "list", query = q) }
        .combine(books) { st, b -> st.copy(books = b) }
        .combine(facets) { st, f -> st.copy(shelves = f.first, series = f.second, genres = f.third.first, tags = f.third.second) }
        .combine(continueReading) { st, c -> st.copy(continueReading = c) }
        .combine(syncInfo) { st, (refreshing, banner) -> st.copy(refreshing = refreshing, banner = banner) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun refresh() {
        val ids = state.value.shownAccountIds
        if (ids.isEmpty()) sync.requestSync(null) else ids.forEach { sync.requestSync(it) }
    }

    /** `accountId` null = merged view of all accounts. */
    fun selectAccount(accountId: String?) {
        viewModelScope.launch {
            settings.update { s -> if (accountId == null) s.copy(mergedLibrary = true) else s.copy(mergedLibrary = false, lastAccountId = accountId) }
        }
    }

    fun toggleLayout() {
        viewModelScope.launch { settings.update { it.copy(libraryLayout = if (it.libraryLayout == "list") "grid" else "list") } }
    }

    fun selectTab(tab: LibraryTab) = query.update { it.copy(tab = tab) }

    fun setSearchOpen(open: Boolean) = query.update {
        if (open) it.copy(searchOpen = true) else it.copy(searchOpen = false, filter = it.filter.copy(search = null))
    }

    fun setSearch(text: String) = query.update { it.copy(filter = it.filter.copy(search = text.ifEmpty { null })) }

    fun setStatus(status: ReadStatus?) = query.update { it.copy(filter = it.filter.copy(status = status)) }

    fun setGenres(genres: Set<String>) = query.update { it.copy(filter = it.filter.copy(genres = genres)) }

    fun setTags(tags: Set<String>) = query.update { it.copy(filter = it.filter.copy(tags = tags)) }

    fun setFormats(formats: Set<String>) = query.update { it.copy(formats = formats) }

    fun setOnlyOffline(only: Boolean) = query.update { it.copy(filter = it.filter.copy(onlyOffline = only)) }

    fun setSort(sort: LibrarySort) = query.update { it.copy(filter = it.filter.copy(sort = sort)) }

    fun toggleDescending() = query.update { it.copy(filter = it.filter.copy(descending = !it.filter.descending)) }

    fun clearFilters() = query.update {
        it.copy(
            filter = it.filter.copy(search = null, status = null, genres = emptySet(), tags = emptySet(), onlyOffline = false),
            formats = emptySet(),
            searchOpen = false,
        )
    }
}
