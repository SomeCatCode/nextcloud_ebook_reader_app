package com.somecatcode.ebookreader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import com.somecatcode.ebookreader.ui.components.ShareBadge
import com.somecatcode.ebookreader.data.repo.FolderTree
import com.somecatcode.ebookreader.data.repo.FolderNode
import androidx.compose.material3.Switch
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.ServerVersions
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibrarySort
import com.somecatcode.ebookreader.data.repo.SeriesInfo
import com.somecatcode.ebookreader.data.repo.SharedFilter
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.data.repo.ShelfKey
import com.somecatcode.ebookreader.ui.components.ConfirmDialog
import com.somecatcode.ebookreader.ui.util.formatRelativeTime
import com.somecatcode.ebookreader.ui.components.BookCover
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.components.MessageBanner
import com.somecatcode.ebookreader.ui.components.OfflineIndicator
import com.somecatcode.ebookreader.ui.util.containerViewModel

/** Library width from which list and detail are shown side by side (tablets, unfolded devices). */
private const val WIDE_BREAKPOINT_DP = 840

class LibraryActions(
    val onRefresh: () -> Unit = {},
    val onSelectAccount: (String?) -> Unit = {},
    val onToggleLayout: () -> Unit = {},
    val onSelectTab: (LibraryTab) -> Unit = {},
    val onSearchOpen: (Boolean) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onStatus: (ReadStatus?) -> Unit = {},
    val onCycleTerm: (String) -> Unit = {},
    val onFlipTerm: (String) -> Unit = {},
    val onRemoveTerm: (String) -> Unit = {},
    val onMatchAny: (Boolean) -> Unit = {},
    val onHideFinished: (Boolean) -> Unit = {},
    val onOnlyOffline: (Boolean) -> Unit = {},
    val onSort: (LibrarySort) -> Unit = {},
    val onToggleDescending: () -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onSaveSmartShelf: (String) -> Unit = {},
    val onUpdateEditingShelf: () -> Unit = {},
    val onStopEditingShelf: () -> Unit = {},
    val onCreateShelf: (String) -> Unit = {},
    val onRenameShelf: (ShelfKey, String) -> Unit = { _, _ -> },
    val onDeleteShelf: (ShelfKey) -> Unit = {},
    val onMoveShelf: (ShelfKey, Int) -> Unit = { _, _ -> },
    val onSharedMode: (SharedFilter) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onFolderUp: () -> Unit = {},
    val onIncludeSubfolders: (Boolean) -> Unit = {},
)

/** Commands handed to the library by other screens (book details, smart shelf). */
object LibraryCommand {
    const val KEY = "libraryCommand"

    /** Show only books matching [term] (`genre:Fantasy`, `author:X`, ...). */
    fun only(term: String) = "only:$term"

    /** Load a smart shelf's filter into the library for editing. */
    fun editSmart(accountId: String, shelfId: Long) = "smart:$shelfId:$accountId"
}

/** Library (grid/list, search, filters, shelves, series). Start destination. */
@Composable
fun LibraryScreen(
    onOpenAccounts: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (accountId: String, fileId: Long) -> Unit,
    onOpenShelf: (accountId: String, shelfId: Long) -> Unit = { _, _ -> },
    onOpenSeries: (accountId: String, name: String) -> Unit = { _, _ -> },
    onReadBook: (accountId: String, fileId: Long) -> Unit = onOpenBook,
    command: String? = null,
    onCommandHandled: () -> Unit = {},
) {
    val vm = containerViewModel { c -> LibraryViewModel(c.accountStore, c.libraryRepository, c.settingsRepository, c.syncEngine, c.shelfRepository) }
    val state by vm.state.collectAsState()
    val actions = remember(vm) {
        LibraryActions(
            onRefresh = vm::refresh, onSelectAccount = vm::selectAccount, onToggleLayout = vm::toggleLayout, onSelectTab = vm::selectTab,
            onSearchOpen = vm::setSearchOpen, onSearch = vm::setSearch, onStatus = vm::setStatus,
            onCycleTerm = vm::cycleTerm, onFlipTerm = vm::flipTerm, onRemoveTerm = vm::removeTerm, onMatchAny = vm::setMatchAny,
            onHideFinished = vm::setHideFinished, onOnlyOffline = vm::setOnlyOffline, onSort = vm::setSort,
            onToggleDescending = vm::toggleDescending, onClearFilters = vm::clearFilters,
            onSaveSmartShelf = vm::saveSmartShelf, onUpdateEditingShelf = vm::updateEditingShelf, onStopEditingShelf = vm::stopEditingShelf,
            onCreateShelf = vm::createShelf, onRenameShelf = vm::renameShelf, onDeleteShelf = vm::deleteShelf, onMoveShelf = vm::moveShelf,
            onSharedMode = vm::setSharedMode, onOpenFolder = vm::openFolder, onFolderUp = vm::folderUp,
            onIncludeSubfolders = vm::setIncludeSubfolders,
        )
    }
    LaunchedEffect(command) {
        val cmd = command ?: return@LaunchedEffect
        when {
            cmd.startsWith("only:") -> vm.onlyTerm(cmd.removePrefix("only:"))
            cmd.startsWith("smart:") -> {
                val rest = cmd.removePrefix("smart:")
                rest.substringBefore(':').toLongOrNull()?.let { vm.editSmartShelf(ShelfKey(rest.substringAfter(':'), it)) }
            }
        }
        onCommandHandled()
    }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(resources.getString(it)) } }
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_BREAKPOINT_DP
    var selected by rememberSaveable(stateSaver = BookKeySaver) { mutableStateOf<BookKey?>(null) }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        LibraryContent(
            state = state,
            actions = actions,
            modifier = modifier,
            onOpenAccounts = onOpenAccounts,
            onOpenDownloads = onOpenDownloads,
            onOpenSettings = onOpenSettings,
            onOpenBook = { key -> if (wide) selected = key else onOpenBook(key.accountId, key.fileId) },
            onOpenShelf = onOpenShelf,
            onOpenSeries = onOpenSeries,
            snackbar = snackbar,
        )
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            content(Modifier.weight(0.55f))
            Box(Modifier.weight(0.45f).fillMaxSize()) {
                val key = selected
                if (key == null) {
                    EmptyState(stringResource(R.string.library_select_book))
                } else {
                    BookDetailScreen(
                        accountId = key.accountId,
                        fileId = key.fileId,
                        onBack = { selected = null },
                        onRead = { onReadBook(key.accountId, key.fileId) },
                        showBack = false,
                        onFilter = vm::onlyTerm,
                    )
                }
            }
        }
    } else {
        content(Modifier)
    }
}

private val BookKeySaver = Saver<BookKey?, List<Any>>(
    save = { it?.let { key -> listOf(key.accountId, key.fileId) } ?: emptyList() },
    restore = { if (it.isEmpty()) null else BookKey(it[0] as String, it[1] as Long) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: LibraryUiState,
    actions: LibraryActions,
    onOpenAccounts: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (BookKey) -> Unit,
    onOpenShelf: (accountId: String, shelfId: Long) -> Unit,
    onOpenSeries: (accountId: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (state.query.searchOpen) {
                        OutlinedTextField(
                            value = state.query.filter.search.orEmpty(),
                            onValueChange = actions.onSearch,
                            placeholder = { Text(stringResource(R.string.library_search_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().testTag("search_field"),
                        )
                    } else {
                        AccountSwitcher(state, actions.onSelectAccount)
                    }
                },
                actions = {
                    if (state.accounts.isNotEmpty()) {
                        IconButton(onClick = { actions.onSearchOpen(!state.query.searchOpen) }, modifier = Modifier.testTag("search_toggle")) {
                            Icon(
                                if (state.query.searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = stringResource(if (state.query.searchOpen) R.string.library_search_close else R.string.library_search),
                            )
                        }
                        if (!state.query.searchOpen) {
                            SortMenu(state, actions)
                            IconButton(onClick = actions.onToggleLayout, modifier = Modifier.testTag("layout_toggle")) {
                                Icon(
                                    if (state.grid) Icons.Filled.ViewList else Icons.Filled.GridView,
                                    contentDescription = stringResource(if (state.grid) R.string.library_layout_list else R.string.library_layout_grid),
                                )
                            }
                        }
                    }
                    OverflowMenu(state, actions.onRefresh, onOpenAccounts, onOpenDownloads, onOpenSettings)
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val banner = state.banner
            if (banner != null && state.accounts.isNotEmpty()) LibraryBannerView(banner, actions.onRefresh, onOpenAccounts)
            when {
                !state.accountsLoaded -> Unit
                state.accounts.isEmpty() -> EmptyState(
                    title = stringResource(R.string.library_welcome_title),
                    body = stringResource(R.string.library_welcome_body),
                    actionLabel = stringResource(R.string.accounts_add),
                    onAction = onOpenAccounts,
                )
                else -> {
                    // up navigation inside the folders tab: the back gesture goes one folder up first
                    BackHandler(enabled = state.tab == LibraryTab.FOLDERS && state.query.folder.isNotEmpty(), onBack = actions.onFolderUp)
                    val tabs = state.visibleTabs
                    PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(state.tab).coerceAtLeast(0), edgePadding = 0.dp) {
                        tabs.forEach { tab ->
                            Tab(
                                selected = state.tab == tab,
                                onClick = { actions.onSelectTab(tab) },
                                text = { Text(stringResource(tabTitle(tab))) },
                                modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
                            )
                        }
                    }
                    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                        when (state.tab) {
                            LibraryTab.BOOKS -> BooksTab(state, actions, onOpenBook)
                            LibraryTab.SERIES -> SeriesTab(state.series, onOpenSeries)
                            LibraryTab.SHARED -> SharedTab(state, actions, onOpenBook)
                            LibraryTab.FOLDERS -> FoldersTab(state, actions, onOpenBook)
                            LibraryTab.SHELVES -> ShelvesTab(state.shelves, actions, onOpenShelf)
                        }
                    }
                }
            }
        }
    }
}

private fun tabTitle(tab: LibraryTab) = when (tab) {
    LibraryTab.BOOKS -> R.string.library_tab_books
    LibraryTab.SHELVES -> R.string.library_tab_shelves
    LibraryTab.SERIES -> R.string.library_tab_series
    LibraryTab.SHARED -> R.string.library_tab_shared
    LibraryTab.FOLDERS -> R.string.library_tab_folders
}

@Composable
private fun LibraryBannerView(banner: LibraryBanner, onRetry: () -> Unit, onOpenAccounts: () -> Unit) {
    when (banner) {
        LibraryBanner.Offline -> MessageBanner(stringResource(R.string.banner_offline))
        is LibraryBanner.AuthExpired -> MessageBanner(
            stringResource(R.string.banner_auth_expired), isError = true,
            actionLabel = stringResource(R.string.account_sign_in_again), onAction = onOpenAccounts,
        )
        LibraryBanner.AppUnavailable -> MessageBanner(
            stringResource(R.string.status_app_unavailable), isError = true,
            actionLabel = stringResource(R.string.nav_accounts), onAction = onOpenAccounts,
        )
        LibraryBanner.ServerError -> MessageBanner(
            stringResource(R.string.banner_server_error), isError = true,
            actionLabel = stringResource(R.string.action_retry), onAction = onRetry,
        )
        is LibraryBanner.ServerOutdated -> MessageBanner(
            stringResource(
                R.string.banner_server_outdated,
                banner.version ?: stringResource(R.string.account_app_version_unknown, ServerVersions.REPORTS_VERSION),
                ServerVersions.RECOMMENDED,
            ),
            actionLabel = stringResource(R.string.nav_accounts), onAction = onOpenAccounts,
        )
    }
}

@Composable
private fun AccountSwitcher(state: LibraryUiState, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val names = state.accountNames
    val label = when {
        state.accounts.isEmpty() -> stringResource(R.string.nav_library)
        state.merged -> stringResource(R.string.library_all_accounts)
        else -> names[state.shownAccountIds.firstOrNull()] ?: stringResource(R.string.nav_library)
    }
    Box {
        TextButton(onClick = { open = true }, enabled = state.accounts.size > 1, modifier = Modifier.testTag("account_switcher")) {
            Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            if (state.accounts.size > 1) Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(names.getValue(account.id)) },
                    onClick = { open = false; onSelect(account.id) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_all_accounts)) },
                onClick = { open = false; onSelect(null) },
                modifier = Modifier.testTag("all_accounts"),
            )
        }
    }
}

@Composable
private fun SortMenu(state: LibraryUiState, actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.library_sort))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        LibrarySort.entries.filter { it != LibrarySort.SHELF }.forEach { sort ->
            DropdownMenuItem(
                text = {
                    val prefix = if (state.query.filter.sort == sort) "✓ " else "    "
                    Text(prefix + stringResource(sortTitle(sort)))
                },
                onClick = { open = false; actions.onSort(sort) },
            )
        }
        DropdownMenuItem(
            text = {
                Text(stringResource(if (state.query.filter.descending) R.string.sort_descending else R.string.sort_ascending))
            },
            onClick = { open = false; actions.onToggleDescending() },
        )
    }
}

private fun sortTitle(sort: LibrarySort) = when (sort) {
    LibrarySort.TITLE -> R.string.sort_title
    LibrarySort.AUTHOR -> R.string.sort_author
    LibrarySort.SERIES -> R.string.sort_series
    LibrarySort.RATING -> R.string.sort_rating
    LibrarySort.ADDED -> R.string.sort_added
    LibrarySort.RECENTLY_READ -> R.string.sort_recently_read
    LibrarySort.SHELF -> R.string.sort_shelf
}

@Composable
private fun OverflowMenu(state: LibraryUiState, onSync: () -> Unit, onAccounts: () -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("overflow")) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (state.accounts.isNotEmpty()) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.sync_now))
                        Text(
                            when {
                                state.refreshing -> stringResource(R.string.sync_running)
                                state.lastSyncAt != null -> stringResource(R.string.sync_last, formatRelativeTime(state.lastSyncAt))
                                else -> stringResource(R.string.sync_never)
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                enabled = !state.refreshing,
                onClick = { open = false; onSync() },
                modifier = Modifier.testTag("sync_now"),
            )
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_accounts)) }, onClick = { open = false; onAccounts() })
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_downloads)) }, onClick = { open = false; onDownloads() })
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_settings)) }, onClick = { open = false; onSettings() })
    }
}

// ---- Books ----------------------------------------------------------------------------------------

@Composable
private fun BooksTab(state: LibraryUiState, actions: LibraryActions, onOpenBook: (BookKey) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("sync_progress"))
        FilterRow(state, actions)
        BookResults(state, actions, onOpenBook, showContinueReading = true, emptyTitle = R.string.library_empty_title, emptyBody = R.string.library_empty_body)
    }
}

/** Books shared with the user and shared by the user, as the plain books list narrowed by [SharedFilter]. */
@Composable
private fun SharedTab(state: LibraryUiState, actions: LibraryActions, onOpenBook: (BookKey) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("sync_progress"))
        val modes = listOf(
            SharedFilter.ANY to R.string.shared_filter_all,
            SharedFilter.INCOMING to R.string.shared_filter_incoming,
            SharedFilter.OUTGOING to R.string.shared_filter_outgoing,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("shared_mode")) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.query.sharedMode == mode,
                    onClick = { actions.onSharedMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    modifier = Modifier.testTag("shared_${mode.name.lowercase()}"),
                ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        FilterRow(state, actions)
        BookResults(state, actions, onOpenBook, showContinueReading = false, emptyTitle = R.string.shared_empty, emptyBody = null)
    }
}

@Composable
private fun BookResults(
    state: LibraryUiState,
    actions: LibraryActions,
    onOpenBook: (BookKey) -> Unit,
    showContinueReading: Boolean,
    emptyTitle: Int,
    emptyBody: Int?,
) {
    when {
        state.books.isEmpty() && state.query.hasActiveFilter -> EmptyState(
            title = stringResource(R.string.library_no_results),
            actionLabel = stringResource(R.string.filter_clear_all),
            onAction = actions.onClearFilters,
        )
        state.books.isEmpty() -> EmptyState(
            title = stringResource(emptyTitle),
            body = emptyBody?.let { stringResource(it) },
            actionLabel = stringResource(R.string.action_refresh),
            onAction = actions.onRefresh,
        )
        state.grid -> BookGrid(state, onOpenBook, showContinueReading)
        else -> BookList(state, onOpenBook)
    }
}

internal fun statusTitle(status: ReadStatus) = when (status) {
    ReadStatus.UNREAD -> R.string.status_unread
    ReadStatus.READING -> R.string.status_reading
    ReadStatus.FINISHED -> R.string.status_finished
}

@Composable
private fun BookGrid(state: LibraryUiState, onOpenBook: (BookKey) -> Unit, showContinueReading: Boolean) {
    val gridState = rememberLazyGridState()
    // A changed query shows a different list: start at the top instead of keeping the old anchor item.
    LaunchedEffect(state.query, state.shownAccountIds) { gridState.scrollToItem(0) }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("book_grid"),
    ) {
        if (showContinueReading && state.continueReading.isNotEmpty() && !state.query.hasActiveFilter) {
            item(span = { GridItemSpan(maxLineSpan) }) { ContinueReadingRow(state.continueReading, onOpenBook) }
        }
        items(state.books, key = { "${it.key.accountId}/${it.key.fileId}" }) { book -> BookGridCell(book, onOpenBook) }
    }
}

/** Cover, title and authors of a book in the grid layout; share badge top left, offline state top right. */
@Composable
internal fun BookGridCell(book: LibraryBook, onOpenBook: (BookKey) -> Unit) {
    Column(Modifier.clickable { onOpenBook(book.key) }.testTag("book_${book.key.fileId}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            BookCover(book, Modifier.fillMaxSize())
            ShareBadge(book.shared, book.sharedOut, book.owner, Modifier.align(Alignment.TopStart).padding(4.dp).testTag("share_badge_${book.key.fileId}"))
            OfflineIndicator(book.offline, Modifier.align(Alignment.TopEnd).padding(4.dp))
            book.percentage?.takeIf { it > 0.0 && it < 1.0 }?.let {
                LinearProgressIndicator({ it.toFloat() }, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (book.authors.isNotEmpty()) {
            Text(
                book.authors.joinToString(), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContinueReadingRow(books: List<LibraryBook>, onOpenBook: (BookKey) -> Unit) {
    Column {
        Text(stringResource(R.string.library_continue_reading), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(books, key = { "c${it.key.accountId}/${it.key.fileId}" }) { book ->
                BookCover(book, Modifier.width(72.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).clickable { onOpenBook(book.key) })
            }
        }
    }
}

@Composable
private fun BookList(state: LibraryUiState, onOpenBook: (BookKey) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.query, state.shownAccountIds) { listState.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("book_list"), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(state.books, key = { "${it.key.accountId}/${it.key.fileId}" }) { book -> BookListRow(book, onOpenBook) }
    }
}

/** One book row of the list layout; the share badge sits next to the offline indicator at the end. */
@Composable
internal fun BookListRow(book: LibraryBook, onOpenBook: (BookKey) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpenBook(book.key) }.padding(horizontal = 16.dp, vertical = 6.dp).testTag("book_${book.key.fileId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(book.authors.joinToString().ifBlank { null }, book.series?.let { s -> book.seriesIndex?.let { "$s #${formatIndex(it)}" } ?: s }).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            book.percentage?.takeIf { it > 0.0 && it < 1.0 }?.let {
                LinearProgressIndicator({ it.toFloat() }, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        ShareBadge(book.shared, book.sharedOut, book.owner, Modifier.padding(end = 4.dp).testTag("share_badge_${book.key.fileId}"))
        OfflineIndicator(book.offline)
    }
}

// ---- Folders --------------------------------------------------------------------------------------

/** Folder browser: breadcrumb, sub-folders with book counts and the books of the shown folder (computed from the synced paths). */
@Composable
private fun FoldersTab(state: LibraryUiState, actions: LibraryActions, onOpenBook: (BookKey) -> Unit) {
    val listing = state.folderListing
    val folder = state.query.folder
    val gridState = rememberLazyGridState()
    LaunchedEffect(folder, state.query.includeSubfolders, state.shownAccountIds) { gridState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("sync_progress"))
        FolderBreadcrumb(folder, actions)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.folders_include_subfolders), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.query.includeSubfolders, onCheckedChange = actions.onIncludeSubfolders,
                modifier = Modifier.testTag("folders_recursive"),
            )
        }
        FilterRow(state, actions)
        val subfolders = listing?.subfolders.orEmpty()
        if (subfolders.isEmpty() && state.books.isEmpty()) {
            if (state.query.hasActiveFilter) {
                EmptyState(
                    title = stringResource(R.string.library_no_results),
                    actionLabel = stringResource(R.string.filter_clear_all),
                    onAction = actions.onClearFilters,
                )
            } else {
                EmptyState(stringResource(if (folder.isEmpty()) R.string.library_empty_title else R.string.folders_empty))
            }
            return@Column
        }
        LazyVerticalGrid(
            state = gridState,
            columns = if (state.grid) GridCells.Adaptive(120.dp) else GridCells.Fixed(1),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (state.grid) 12.dp else 4.dp),
            modifier = Modifier.fillMaxSize().testTag("folder_content"),
        ) {
            items(subfolders, key = { "f/${it.path}" }, span = { GridItemSpan(maxLineSpan) }) { node ->
                FolderRow(node) { actions.onOpenFolder(node.path) }
            }
            items(state.books, key = { "${it.key.accountId}/${it.key.fileId}" }) { book ->
                if (state.grid) BookGridCell(book, onOpenBook) else BookListRow(book, onOpenBook)
            }
        }
    }
}

@Composable
private fun FolderBreadcrumb(folder: String, actions: LibraryActions) {
    val crumbs = FolderTree.breadcrumb(folder)
    val listState = rememberLazyListState()
    LaunchedEffect(folder) { listState.scrollToItem(crumbs.size) }
    Row(Modifier.fillMaxWidth().testTag("folder_breadcrumb"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onFolderUp, enabled = folder.isNotEmpty(), modifier = Modifier.testTag("folder_up")) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.folders_up))
        }
        LazyRow(state = listState, verticalAlignment = Alignment.CenterVertically) {
            item {
                TextButton(onClick = { actions.onOpenFolder("") }) { Text(stringResource(R.string.folders_root)) }
            }
            items(crumbs, key = { it.second }) { (name, path) ->
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { actions.onOpenFolder(path) }) { Text(name, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun FolderRow(node: FolderNode, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("folder_${node.path}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp).size(32.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.books_count, node.totalCount, node.totalCount),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatIndex(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

// ---- Shelves and series --------------------------------------------------------------------------

@Composable
private fun ShelvesTab(shelves: List<ShelfInfo>, actions: LibraryActions, onOpenShelf: (String, Long) -> Unit) {
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<ShelfInfo?>(null) }
    var delete by remember { mutableStateOf<ShelfInfo?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("shelf_list"), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            TextButton(onClick = { create = true }, modifier = Modifier.padding(horizontal = 8.dp).testTag("shelf_create")) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.shelf_new), Modifier.padding(start = 8.dp))
            }
        }
        if (shelves.isEmpty()) {
            item { EmptyState(stringResource(R.string.shelves_empty)) }
        }
        itemsIndexed(shelves, key = { _, it -> "${it.key.accountId}/${it.key.shelfId}" }) { index, shelf ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    CollectionRow(
                        accountId = shelf.key.accountId,
                        coverFileId = shelf.coverFileIds.firstOrNull(),
                        title = shelf.name,
                        subtitle = pluralStringResource(R.plurals.books_count, shelf.count, shelf.count) +
                            if (shelf.smart) " · " + stringResource(R.string.shelf_smart) else "",
                        onClick = { onOpenShelf(shelf.key.accountId, shelf.key.shelfId) },
                    )
                }
                ShelfMenu(
                    canMoveUp = index > 0 && shelves[index - 1].key.accountId == shelf.key.accountId,
                    canMoveDown = index < shelves.lastIndex && shelves[index + 1].key.accountId == shelf.key.accountId,
                    onRename = { rename = shelf },
                    onDelete = { delete = shelf },
                    onMove = { actions.onMoveShelf(shelf.key, it) },
                    tag = "shelf_menu_${shelf.key.shelfId}",
                )
            }
        }
    }
    if (create) {
        NameDialog(stringResource(R.string.shelf_new), "", onDismiss = { create = false }) { create = false; actions.onCreateShelf(it) }
    }
    rename?.let { shelf ->
        NameDialog(stringResource(R.string.shelf_rename), shelf.name, onDismiss = { rename = null }) { rename = null; actions.onRenameShelf(shelf.key, it) }
    }
    delete?.let { shelf ->
        ConfirmDialog(
            title = stringResource(R.string.shelf_delete),
            text = stringResource(R.string.shelf_delete_confirm, shelf.name),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = { delete = null; actions.onDeleteShelf(shelf.key) },
            onDismiss = { delete = null },
        )
    }
}

@Composable
internal fun ShelfMenu(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: ((Int) -> Unit)?,
    tag: String,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.shelf_rename)) }, onClick = { open = false; onRename() })
            if (onMove != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.shelf_move_up)) }, enabled = canMoveUp, onClick = { open = false; onMove(-1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.shelf_move_down)) }, enabled = canMoveDown, onClick = { open = false; onMove(1) })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.shelf_delete)) }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun SeriesTab(series: List<SeriesInfo>, onOpenSeries: (String, String) -> Unit) {
    if (series.isEmpty()) {
        EmptyState(stringResource(R.string.series_empty))
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("series_list"), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(series, key = { "${it.accountId}/${it.name}" }) { s ->
            CollectionRow(
                accountId = s.accountId,
                coverFileId = s.coverFileIds.firstOrNull(),
                title = s.name,
                subtitle = pluralStringResource(R.plurals.volumes_count, s.count, s.count) +
                    " · " + stringResource(R.string.series_read_count, s.readCount, s.count),
                onClick = { onOpenSeries(s.accountId, s.name) },
                shared = s.shared, sharedOut = s.sharedOut,
            )
        }
    }
}

@Composable
private fun CollectionRow(
    accountId: String,
    coverFileId: Long?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    shared: Boolean = false,
    sharedOut: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(accountId, coverFileId ?: 0L, coverFileId != null, null, Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ShareBadge(shared, sharedOut, null, Modifier.padding(start = 8.dp).testTag("share_badge_$title"))
    }
}
