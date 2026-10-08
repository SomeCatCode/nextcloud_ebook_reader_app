package com.somecatcode.ebookreader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.ServerFeature
import com.somecatcode.ebookreader.data.ServerVersions
import com.somecatcode.ebookreader.data.account.Account
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.api.SmartQueryDto
import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.FolderListing
import com.somecatcode.ebookreader.data.repo.FolderTree
import com.somecatcode.ebookreader.data.repo.SharedFilter
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibraryFacets
import com.somecatcode.ebookreader.data.repo.LibraryFilter
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.LibrarySort
import com.somecatcode.ebookreader.data.repo.SeriesInfo
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.data.repo.ShelfKey
import com.somecatcode.ebookreader.data.repo.ShelfRepository
import com.somecatcode.ebookreader.data.sync.SyncEngine
import com.somecatcode.ebookreader.data.sync.SyncError
import com.somecatcode.ebookreader.data.sync.SyncState
import com.somecatcode.ebookreader.ui.util.TermState
import com.somecatcode.ebookreader.ui.util.cycle
import com.somecatcode.ebookreader.ui.util.flip
import com.somecatcode.ebookreader.ui.util.termType
import com.somecatcode.ebookreader.ui.util.withTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Order as in the web app: books (all / continue / unread / finished via the status filter), series, shared,
 * folders, shelves. [SHARED] and [FOLDERS] need server 0.10.0 (see [LibraryUiState.visibleTabs]).
 */
enum class LibraryTab { BOOKS, SERIES, SHARED, FOLDERS, SHELVES }

sealed interface LibraryBanner {
    data object Offline : LibraryBanner
    data class AuthExpired(val accountId: String) : LibraryBanner
    data object AppUnavailable : LibraryBanner
    data object ServerError : LibraryBanner
    /** The server app is older than [com.somecatcode.ebookreader.data.ServerVersions.RECOMMENDED] (null = unknown version). */
    data class ServerOutdated(val version: String?) : LibraryBanner
}

/** What the user selected; separate from the data so the query flows can react to it. */
data class LibraryQuery(
    val tab: LibraryTab = LibraryTab.BOOKS,
    val searchOpen: Boolean = false,
    /** Filter without [LibraryFilter.hideFinished] (that one is a persisted view option). */
    val filter: LibraryFilter = LibraryFilter(),
    /** Smart shelf whose saved query is shown and can be updated. */
    val editingShelf: ShelfKey? = null,
    /** "Shared" tab: all / shared with me / shared by me. */
    val sharedMode: SharedFilter = SharedFilter.ANY,
    /** "Folders" tab: shown folder ("" = top level) and whether books of subfolders are listed too. */
    val folder: String = "",
    val includeSubfolders: Boolean = false,
) {
    val activeFilterCount: Int
        get() = filter.include.size + filter.exclude.size + listOf(filter.status != null, filter.onlyOffline).count { it }
    val hasActiveFilter: Boolean get() = activeFilterCount > 0 || !filter.search.isNullOrBlank()

    /** The current filter as a smart shelf query (shelf terms are not allowed there). */
    fun toSmartQuery(): SmartQueryDto = SmartQueryDto(
        include = filter.include.filterNot { termType(it) == "shelf" },
        exclude = filter.exclude.filterNot { termType(it) == "shelf" },
        match = if (filter.matchAny) "any" else "all",
        search = filter.search?.trim().orEmpty(),
        status = filter.status,
        sort = sortWire(filter.sort),
        order = if (filter.descending) "desc" else "asc",
    )
}

internal fun sortWire(sort: LibrarySort) = when (sort) {
    LibrarySort.TITLE -> "title"
    LibrarySort.AUTHOR -> "author"
    LibrarySort.SERIES -> "series"
    LibrarySort.RATING -> "rating"
    LibrarySort.ADDED -> "added"
    LibrarySort.RECENTLY_READ -> "read"
    LibrarySort.SHELF -> "shelf"
}

internal fun sortFromWire(value: String) = when (value) {
    "author" -> LibrarySort.AUTHOR
    "series" -> LibrarySort.SERIES
    "rating" -> LibrarySort.RATING
    "added" -> LibrarySort.ADDED
    "read" -> LibrarySort.RECENTLY_READ
    else -> LibrarySort.TITLE
}

/** Web defaults: newest/best first for date, recently read and rating, otherwise ascending. */
internal fun defaultDescending(sort: LibrarySort) =
    sort == LibrarySort.ADDED || sort == LibrarySort.RECENTLY_READ || sort == LibrarySort.RATING

/** The filter the books list of the selected tab is computed with. */
internal fun LibraryQuery.effectiveFilter(hideFinished: Boolean): LibraryFilter = when (tab) {
    // the persisted "hide finished" option belongs to the plain books list only
    LibraryTab.SHARED -> filter.copy(shared = sharedMode, hideFinished = false)
    LibraryTab.FOLDERS -> filter.copy(hideFinished = false)
    else -> filter.copy(hideFinished = hideFinished)
}

/** Filter that a saved smart query stands for. */
internal fun SmartQueryDto.toFilter(): LibraryFilter = LibraryFilter(
    search = search.ifBlank { null },
    status = status,
    include = include,
    exclude = exclude,
    matchAny = match == "any",
    sort = sortFromWire(sort),
    descending = order == "desc",
)

data class LibraryUiState(
    val accountsLoaded: Boolean = false,
    val accounts: List<Account> = emptyList(),
    /** Accounts whose library is shown. */
    val shownAccountIds: List<String> = emptyList(),
    val merged: Boolean = false,
    val grid: Boolean = true,
    val hideFinished: Boolean = true,
    val query: LibraryQuery = LibraryQuery(),
    /** Books of the selected tab; in the folders tab the books of the shown folder. */
    val books: List<LibraryBook> = emptyList(),
    /** Folders tab: sub-folders and books of the shown folder. */
    val folderListing: FolderListing<LibraryBook>? = null,
    val continueReading: List<LibraryBook> = emptyList(),
    val shelves: List<ShelfInfo> = emptyList(),
    val series: List<SeriesInfo> = emptyList(),
    val facets: LibraryFacets = LibraryFacets(),
    val refreshing: Boolean = false,
    val banner: LibraryBanner? = null,
    /** Oldest last successful sync of the shown accounts (null = at least one never synced). */
    val lastSyncAt: Long? = null,
) {
    /** Tabs shown: the new views need server 0.10.0 on at least one of the shown accounts. */
    val visibleTabs: List<LibraryTab>
        get() {
            val shown = accounts.filter { it.id in shownAccountIds }
            val shared = shown.any { ServerFeature.SHARED_VIEW.availableOn(it.appVersion) }
            val folders = shown.any { ServerFeature.FOLDERS_VIEW.availableOn(it.appVersion) }
            return LibraryTab.entries.filter { (it != LibraryTab.SHARED || shared) && (it != LibraryTab.FOLDERS || folders) }
        }
    /** The selected tab, or books when an older server hides it. */
    val tab: LibraryTab get() = query.tab.takeIf { it in visibleTabs } ?: LibraryTab.BOOKS
    val accountNames: Map<String, String> get() = accounts.associate { it.id to (it.displayName?.takeIf(String::isNotBlank) ?: it.loginName) }
    val genres: List<FacetCount> get() = facets.genres
    val tags: List<FacetCount> get() = facets.tags
    val editingShelf: ShelfInfo? get() = query.editingShelf?.let { k -> shelves.firstOrNull { it.key == k } }

    /** The smart shelf being edited differs from the current filter. */
    val editingShelfChanged: Boolean get() = editingShelf?.query?.let { it != query.toSmartQuery() } ?: false
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    accountStore: AccountStore,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val sync: SyncEngine,
    private val shelfRepo: ShelfRepository? = null,
) : ViewModel() {

    private val query = MutableStateFlow(LibraryQuery())

    private val accounts = accountStore.accounts

    /** One-off messages (string resource ids) for a snackbar. */
    private val messageChannel = Channel<Int>(Channel.BUFFERED)
    val messages: Flow<Int> = messageChannel.receiveAsFlow()

    /** Single account (selected, default first) or all accounts when merged. */
    private val shownIds: Flow<List<String>> = combine(accounts, settings.settings) { list, s ->
        when {
            list.isEmpty() -> emptyList()
            s.mergedLibrary -> list.map { it.id }
            else -> listOf((list.firstOrNull { it.id == s.lastAccountId } ?: list.first()).id)
        }
    }.distinctUntilChanged()

    private val hideFinished: Flow<Boolean> = settings.settings.map { it.hideFinished }.distinctUntilChanged()

    private data class TabBooks(val books: List<LibraryBook>, val listing: FolderListing<LibraryBook>? = null)

    /** Folder shown by the folders tab; null in all other tabs (so browsing folders does not reload the other lists). */
    private data class FolderView(val folder: String, val recursive: Boolean)

    private val tabBooks: Flow<TabBooks> = combine(shownIds, query, hideFinished) { ids, q, hide ->
        Triple(ids, q.effectiveFilter(hide), if (q.tab == LibraryTab.FOLDERS) FolderView(FolderTree.normalize(q.folder), q.includeSubfolders) else null)
    }
        .distinctUntilChanged()
        .flatMapLatest { (ids, filter, folderView) ->
            if (ids.isEmpty()) {
                flowOf(TabBooks(emptyList()))
            } else {
                library.books(ids, filter).map { list ->
                    if (folderView == null) {
                        TabBooks(list)
                    } else {
                        // the folder structure is computed locally from the synced book paths
                        val listing = FolderTree.list(list, folderView.folder, folderView.recursive) { it.path }
                        TabBooks(listing.items, listing)
                    }
                }
            }
        }

    private data class Collections(val shelves: List<ShelfInfo>, val series: List<SeriesInfo>, val facets: LibraryFacets)

    private val collections: Flow<Collections> =
        shownIds.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(Collections(emptyList(), emptyList(), LibraryFacets()))
            else combine(library.shelves(ids), library.series(ids), library.facets(ids)) { sh, se, f -> Collections(sh, se, f) }
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
    ) { list, s, ids, q ->
        val shown = list.filter { it.id in ids }
        LibraryUiState(
            accountsLoaded = true, accounts = list, shownAccountIds = ids, merged = s.mergedLibrary, grid = s.libraryLayout != "list",
            hideFinished = s.hideFinished, query = q,
            lastSyncAt = if (shown.isEmpty() || shown.any { it.lastSyncAt == null }) null else shown.mapNotNull { it.lastSyncAt }.minOrNull(),
        )
    }
        .combine(tabBooks) { st, b -> st.copy(books = b.books, folderListing = b.listing) }
        .combine(collections) { st, c -> st.copy(shelves = c.shelves, series = c.series, facets = c.facets) }
        .combine(continueReading) { st, c -> st.copy(continueReading = c) }
        .combine(syncInfo) { st, (refreshing, banner) ->
            // An outdated server app is only a hint; real problems (offline, auth, errors) take precedence.
            val outdated = st.accounts.firstOrNull { it.id in st.shownAccountIds && ServerVersions.isOutdated(it.appVersion) }
            st.copy(refreshing = refreshing, banner = banner ?: outdated?.let { LibraryBanner.ServerOutdated(it.appVersion) })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun refresh() {
        val ids = state.value.shownAccountIds
        if (ids.isEmpty()) sync.requestSync(null) else ids.forEach { sync.requestSync(it) }
    }

    /** `accountId` null = merged view of all accounts. */
    fun selectAccount(accountId: String?) {
        // Shelf ids belong to one account: drop shelf terms and the edited shelf when the account changes.
        query.update {
            it.copy(
                editingShelf = null,
                folder = "",
                filter = it.filter.copy(
                    include = it.filter.include.filterNot { t -> termType(t) == "shelf" },
                    exclude = it.filter.exclude.filterNot { t -> termType(t) == "shelf" },
                ),
            )
        }
        viewModelScope.launch {
            settings.update { s -> if (accountId == null) s.copy(mergedLibrary = true) else s.copy(mergedLibrary = false, lastAccountId = accountId) }
        }
    }

    fun toggleLayout() {
        viewModelScope.launch { settings.update { it.copy(libraryLayout = if (it.libraryLayout == "list") "grid" else "list") } }
    }

    fun setHideFinished(hide: Boolean) {
        viewModelScope.launch { settings.update { it.copy(hideFinished = hide) } }
    }

    fun selectTab(tab: LibraryTab) = query.update { it.copy(tab = tab) }

    fun setSharedMode(mode: SharedFilter) = query.update { it.copy(sharedMode = mode) }

    fun openFolder(path: String) = query.update { it.copy(folder = FolderTree.normalize(path)) }

    /** One level up; no effect at the top level. */
    fun folderUp() = query.update { it.copy(folder = FolderTree.parent(it.folder) ?: "") }

    fun setIncludeSubfolders(include: Boolean) = query.update { it.copy(includeSubfolders = include) }

    fun setSearchOpen(open: Boolean) = query.update {
        if (open) it.copy(searchOpen = true) else it.copy(searchOpen = false, filter = it.filter.copy(search = null))
    }

    fun setSearch(text: String) = query.update { it.copy(filter = it.filter.copy(search = text.ifEmpty { null })) }

    fun setStatus(status: ReadStatus?) = query.update { it.copy(filter = it.filter.copy(status = status)) }

    /** off → include → exclude → off. */
    fun cycleTerm(term: String) = query.update { it.copy(filter = it.filter.cycle(term)) }

    /** include ⇄ exclude. */
    fun flipTerm(term: String) = query.update { it.copy(filter = it.filter.flip(term)) }

    fun removeTerm(term: String) = query.update { it.copy(filter = it.filter.withTerm(term, TermState.OFF)) }

    /** "Only this": the term becomes the single filter (tapping a genre/author/... in the book details). */
    fun onlyTerm(term: String) = query.update {
        it.copy(
            tab = LibraryTab.BOOKS, searchOpen = false, editingShelf = null,
            filter = it.filter.copy(include = listOf(term), exclude = emptyList(), status = null, search = null, onlyOffline = false),
        )
    }

    fun setMatchAny(any: Boolean) = query.update { it.copy(filter = it.filter.copy(matchAny = any)) }

    fun setOnlyOffline(only: Boolean) = query.update { it.copy(filter = it.filter.copy(onlyOffline = only)) }

    fun setSort(sort: LibrarySort) = query.update { it.copy(filter = it.filter.copy(sort = sort, descending = defaultDescending(sort))) }

    fun toggleDescending() = query.update { it.copy(filter = it.filter.copy(descending = !it.filter.descending)) }

    fun clearFilters() = query.update {
        it.copy(
            filter = it.filter.copy(search = null, status = null, include = emptyList(), exclude = emptyList(), matchAny = false, onlyOffline = false),
            searchOpen = false,
            editingShelf = null,
        )
    }

    // ---- smart shelves ----------------------------------------------------------------------------

    /** Shows a smart shelf's saved filter in the library so it can be refined and saved back. */
    fun editSmartShelf(key: ShelfKey) {
        viewModelScope.launch {
            val shelf = library.shelves(listOf(key.accountId)).first().firstOrNull { it.key == key } ?: return@launch
            val saved = shelf.query ?: return@launch
            if (key.accountId !in state.value.shownAccountIds) selectAccount(key.accountId)
            query.update { it.copy(tab = LibraryTab.BOOKS, searchOpen = saved.search.isNotBlank(), filter = saved.toFilter(), editingShelf = key) }
        }
    }

    fun stopEditingShelf() = query.update { it.copy(editingShelf = null) }

    fun saveSmartShelf(name: String) = shelfAction { repo ->
        val accountId = state.value.shownAccountIds.firstOrNull() ?: return@shelfAction
        val key = repo.create(accountId, name, query.value.toSmartQuery())
        query.update { it.copy(editingShelf = key) }
    }

    fun updateEditingShelf() = shelfAction { repo ->
        val key = query.value.editingShelf ?: return@shelfAction
        repo.updateQuery(key, query.value.toSmartQuery())
    }

    // ---- shelves ----------------------------------------------------------------------------------

    fun createShelf(name: String) = shelfAction { repo ->
        val accountId = state.value.shownAccountIds.firstOrNull() ?: return@shelfAction
        repo.create(accountId, name)
    }

    fun renameShelf(key: ShelfKey, name: String) = shelfAction { it.rename(key, name) }

    fun deleteShelf(key: ShelfKey) = shelfAction { repo ->
        repo.delete(key)
        query.update { q -> if (q.editingShelf == key) q.copy(editingShelf = null) else q }
    }

    fun moveShelf(key: ShelfKey, delta: Int) = shelfAction { it.move(key, delta) }

    private fun shelfAction(block: suspend (ShelfRepository) -> Unit) {
        val repo = shelfRepo ?: return
        viewModelScope.launch {
            try {
                block(repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(shelfErrorMessage(e))
            }
        }
    }
}

/** User-facing message for a failed shelf call. */
internal fun shelfErrorMessage(e: Exception): Int = when (e) {
    is ApiException.Network -> R.string.shelf_error_offline
    is ApiException.BadRequest -> R.string.shelf_error_name
    else -> R.string.shelf_error_generic
}
