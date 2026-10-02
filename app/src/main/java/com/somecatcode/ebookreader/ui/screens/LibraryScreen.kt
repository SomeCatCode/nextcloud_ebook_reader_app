package com.somecatcode.ebookreader.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibrarySort
import com.somecatcode.ebookreader.data.repo.SeriesInfo
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.ui.components.BookCover
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.components.MessageBanner
import com.somecatcode.ebookreader.ui.components.MultiSelectDialog
import com.somecatcode.ebookreader.ui.components.OfflineIndicator
import com.somecatcode.ebookreader.ui.util.containerViewModel

/** Library width from which list and detail are shown side by side (tablets, unfolded devices). */
private const val WIDE_BREAKPOINT_DP = 840

class LibraryActions(
    val onRefresh: () -> Unit,
    val onSelectAccount: (String?) -> Unit,
    val onToggleLayout: () -> Unit,
    val onSelectTab: (LibraryTab) -> Unit,
    val onSearchOpen: (Boolean) -> Unit,
    val onSearch: (String) -> Unit,
    val onStatus: (ReadStatus?) -> Unit,
    val onGenres: (Set<String>) -> Unit,
    val onTags: (Set<String>) -> Unit,
    val onFormats: (Set<String>) -> Unit,
    val onOnlyOffline: (Boolean) -> Unit,
    val onSort: (LibrarySort) -> Unit,
    val onToggleDescending: () -> Unit,
    val onClearFilters: () -> Unit,
)

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
) {
    val vm = containerViewModel { c -> LibraryViewModel(c.accountStore, c.libraryRepository, c.settingsRepository, c.syncEngine) }
    val state by vm.state.collectAsState()
    val actions = remember(vm) {
        LibraryActions(
            vm::refresh, vm::selectAccount, vm::toggleLayout, vm::selectTab, vm::setSearchOpen, vm::setSearch, vm::setStatus,
            vm::setGenres, vm::setTags, vm::setFormats, vm::setOnlyOffline, vm::setSort, vm::toggleDescending, vm::clearFilters,
        )
    }
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
) {
    Scaffold(
        modifier = modifier,
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
                    OverflowMenu(onOpenAccounts, onOpenDownloads, onOpenSettings)
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
                    PrimaryTabRow(selectedTabIndex = state.query.tab.ordinal) {
                        LibraryTab.entries.forEach { tab ->
                            Tab(
                                selected = state.query.tab == tab,
                                onClick = { actions.onSelectTab(tab) },
                                text = { Text(stringResource(tabTitle(tab))) },
                                modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
                            )
                        }
                    }
                    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                        when (state.query.tab) {
                            LibraryTab.BOOKS -> BooksTab(state, actions, onOpenBook)
                            LibraryTab.SHELVES -> ShelvesTab(state.shelves, onOpenShelf)
                            LibraryTab.SERIES -> SeriesTab(state.series, onOpenSeries)
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
        LibrarySort.entries.forEach { sort ->
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
}

@Composable
private fun OverflowMenu(onAccounts: () -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("overflow")) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_accounts)) }, onClick = { open = false; onAccounts() })
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_downloads)) }, onClick = { open = false; onDownloads() })
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_settings)) }, onClick = { open = false; onSettings() })
    }
}

// ---- Books ----------------------------------------------------------------------------------------

@Composable
private fun BooksTab(state: LibraryUiState, actions: LibraryActions, onOpenBook: (BookKey) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        FilterRow(state, actions)
        when {
            state.books.isEmpty() && state.query.hasActiveFilter -> EmptyState(
                title = stringResource(R.string.library_no_results),
                actionLabel = stringResource(R.string.filter_clear_all),
                onAction = actions.onClearFilters,
            )
            state.books.isEmpty() -> EmptyState(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
                actionLabel = stringResource(R.string.action_refresh),
                onAction = actions.onRefresh,
            )
            state.grid -> BookGrid(state, onOpenBook)
            else -> BookList(state, onOpenBook)
        }
    }
}

@Composable
private fun FilterRow(state: LibraryUiState, actions: LibraryActions) {
    val query = state.query
    var dialog by remember { mutableStateOf<String?>(null) }
    var statusMenu by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                FilterChip(
                    selected = query.filter.status != null,
                    onClick = { statusMenu = true },
                    label = { Text(query.filter.status?.let { stringResource(statusTitle(it)) } ?: stringResource(R.string.filter_status)) },
                    modifier = Modifier.testTag("filter_status"),
                )
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filter_any)) },
                        onClick = { statusMenu = false; actions.onStatus(null) },
                    )
                    ReadStatus.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(stringResource(statusTitle(status))) },
                            onClick = { statusMenu = false; actions.onStatus(status) },
                            modifier = Modifier.testTag("status_${status.name.lowercase()}"),
                        )
                    }
                }
            }
        }
        item {
            FilterChip(
                selected = query.filter.genres.isNotEmpty(),
                onClick = { dialog = "genres" },
                label = { Text(countLabel(stringResource(R.string.filter_genres), query.filter.genres.size)) },
                modifier = Modifier.testTag("filter_genres"),
            )
        }
        item {
            FilterChip(
                selected = query.filter.tags.isNotEmpty(),
                onClick = { dialog = "tags" },
                label = { Text(countLabel(stringResource(R.string.filter_tags), query.filter.tags.size)) },
                modifier = Modifier.testTag("filter_tags"),
            )
        }
        item {
            FilterChip(
                selected = query.formats.isNotEmpty(),
                onClick = { dialog = "formats" },
                label = { Text(countLabel(stringResource(R.string.filter_format), query.formats.size)) },
                modifier = Modifier.testTag("filter_format"),
            )
        }
        item {
            FilterChip(
                selected = query.filter.onlyOffline,
                onClick = { actions.onOnlyOffline(!query.filter.onlyOffline) },
                label = { Text(stringResource(R.string.filter_offline_only)) },
                modifier = Modifier.testTag("filter_offline"),
            )
        }
        if (query.hasActiveFilter) {
            item { TextButton(onClick = actions.onClearFilters) { Text(stringResource(R.string.filter_clear_all)) } }
        }
    }
    when (dialog) {
        "genres" -> MultiSelectDialog(
            title = stringResource(R.string.filter_genres),
            options = state.genres.map { it.name to it.count },
            selected = query.filter.genres,
            onDismiss = { dialog = null },
            onConfirm = { actions.onGenres(it); dialog = null },
        )
        "tags" -> MultiSelectDialog(
            title = stringResource(R.string.filter_tags),
            options = state.tags.map { it.name to it.count },
            selected = query.filter.tags,
            onDismiss = { dialog = null },
            onConfirm = { actions.onTags(it); dialog = null },
        )
        "formats" -> MultiSelectDialog(
            title = stringResource(R.string.filter_format),
            options = KnownFormats.map { it to null },
            selected = query.formats,
            onDismiss = { dialog = null },
            onConfirm = { actions.onFormats(it); dialog = null },
        )
    }
}

private fun countLabel(label: String, count: Int) = if (count > 0) "$label ($count)" else label

internal fun statusTitle(status: ReadStatus) = when (status) {
    ReadStatus.UNREAD -> R.string.status_unread
    ReadStatus.READING -> R.string.status_reading
    ReadStatus.FINISHED -> R.string.status_finished
}

@Composable
private fun BookGrid(state: LibraryUiState, onOpenBook: (BookKey) -> Unit) {
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
        if (state.continueReading.isNotEmpty() && !state.query.hasActiveFilter) {
            item(span = { GridItemSpan(maxLineSpan) }) { ContinueReadingRow(state.continueReading, onOpenBook) }
        }
        items(state.books, key = { "${it.key.accountId}/${it.key.fileId}" }) { book ->
            Column(Modifier.clickable { onOpenBook(book.key) }.testTag("book_${book.key.fileId}")) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
                    BookCover(book, Modifier.fillMaxSize())
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
        items(state.books, key = { "${it.key.accountId}/${it.key.fileId}" }) { book ->
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
                OfflineIndicator(book.offline)
            }
        }
    }
}

internal fun formatIndex(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

// ---- Shelves and series --------------------------------------------------------------------------

@Composable
private fun ShelvesTab(shelves: List<ShelfInfo>, onOpenShelf: (String, Long) -> Unit) {
    if (shelves.isEmpty()) {
        EmptyState(stringResource(R.string.shelves_empty))
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("shelf_list"), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(shelves, key = { "${it.key.accountId}/${it.key.shelfId}" }) { shelf ->
            CollectionRow(
                accountId = shelf.key.accountId,
                coverFileId = shelf.coverFileIds.firstOrNull(),
                title = shelf.name,
                subtitle = pluralStringResource(R.plurals.books_count, shelf.count, shelf.count) +
                    if (shelf.smart) " · " + stringResource(R.string.shelf_smart) else "",
                onClick = { onOpenShelf(shelf.key.accountId, shelf.key.shelfId) },
            )
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
            )
        }
    }
}

@Composable
private fun CollectionRow(accountId: String, coverFileId: Long?, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(accountId, coverFileId ?: 0L, coverFileId != null, null, Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
