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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.ui.components.BookCover
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.components.OfflineIndicator
import com.somecatcode.ebookreader.ui.util.containerViewModel

/**
 * Start screen: rows of covers for continue reading, synced offline, suggestions and a few random shelves.
 * [onOpenLibrary] gets a [LibraryCommand] (or null for the plain library) for the "show all" actions.
 */
@Composable
fun HomeScreen(
    onOpenLibrary: (command: String?) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (accountId: String, fileId: Long) -> Unit,
    onOpenShelf: (accountId: String, shelfId: Long) -> Unit,
) {
    val vm = containerViewModel { c -> HomeViewModel(c.accountStore, c.libraryRepository, c.settingsRepository, c.syncEngine) }
    val state by vm.state.collectAsState()
    HomeContent(
        state = state,
        onRefresh = vm::refresh,
        onOpenLibrary = onOpenLibrary,
        onOpenAccounts = onOpenAccounts,
        onOpenDownloads = onOpenDownloads,
        onOpenSettings = onOpenSettings,
        onOpenBook = { onOpenBook(it.accountId, it.fileId) },
        onOpenShelf = onOpenShelf,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onOpenLibrary: (String?) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (BookKey) -> Unit,
    onOpenShelf: (accountId: String, shelfId: Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_home)) },
                actions = {
                    if (state.hasAccounts) {
                        IconButton(onClick = { onOpenLibrary(null) }, modifier = Modifier.testTag("home_open_library")) {
                            Icon(Icons.Filled.LibraryBooks, contentDescription = stringResource(R.string.nav_library))
                        }
                    }
                    HomeOverflow(state.hasAccounts, state.refreshing, onRefresh, onOpenAccounts, onOpenDownloads, onOpenSettings)
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val sections = state.sections
            when {
                !state.loaded -> Unit
                !state.hasAccounts -> EmptyState(
                    title = stringResource(R.string.library_welcome_title),
                    body = stringResource(R.string.library_welcome_body),
                    actionLabel = stringResource(R.string.accounts_add),
                    onAction = onOpenAccounts,
                )
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    if (sections.isEmpty) {
                        EmptyState(
                            title = stringResource(R.string.home_empty_title),
                            body = stringResource(R.string.home_empty_body),
                            actionLabel = stringResource(R.string.home_open_library),
                            onAction = { onOpenLibrary(null) },
                            modifier = Modifier.testTag("home_empty"),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize().testTag("home_list"), contentPadding = PaddingValues(vertical = 8.dp)) {
                            if (sections.continueReading.isNotEmpty()) item(key = "continue") {
                                CoverRow("continue", stringResource(R.string.home_continue_reading), sections.continueReading, onOpenBook, onShowAll = { onOpenLibrary(LibraryCommand.reading()) })
                            }
                            if (sections.offline.isNotEmpty()) item(key = "offline") {
                                CoverRow("offline", stringResource(R.string.home_offline), sections.offline, onOpenBook, onShowAll = { onOpenLibrary(LibraryCommand.offline()) })
                            }
                            if (sections.suggestions.isNotEmpty()) item(key = "suggestions") {
                                CoverRow("suggestions", stringResource(R.string.home_suggestions), sections.suggestions, onOpenBook, onShowAll = null)
                            }
                            items(sections.shelves, key = { "shelf/${it.shelf.key.accountId}/${it.shelf.key.shelfId}" }) { shelf ->
                                CoverRow(
                                    "shelf_${shelf.shelf.key.shelfId}", shelf.shelf.name, shelf.books, onOpenBook,
                                    onShowAll = { onOpenShelf(shelf.shelf.key.accountId, shelf.shelf.key.shelfId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Header with an optional "show all" action and a horizontal row of covers. */
@Composable
private fun CoverRow(tag: String, title: String, books: List<LibraryBook>, onOpenBook: (BookKey) -> Unit, onShowAll: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("home_section_$tag")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onShowAll != null) {
                TextButton(onClick = onShowAll, modifier = Modifier.testTag("home_show_all_$tag")) { Text(stringResource(R.string.home_show_all)) }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { "${it.key.accountId}/${it.key.fileId}" }) { book -> HomeBookCard(book, onOpenBook) }
        }
    }
}

/** Cover with progress bar and offline state, title below. Tap opens the book details (as in the library). */
@Composable
private fun HomeBookCard(book: LibraryBook, onOpenBook: (BookKey) -> Unit) {
    Column(Modifier.width(110.dp).clickable { onOpenBook(book.key) }.testTag("home_book_${book.key.fileId}")) {
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

@Composable
private fun HomeOverflow(
    hasAccounts: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAccounts: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("overflow")) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (hasAccounts) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_now)) },
                leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null) },
                enabled = !refreshing,
                onClick = { open = false; onRefresh() },
                modifier = Modifier.testTag("sync_now"),
            )
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_accounts)) }, onClick = { open = false; onAccounts() })
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_downloads)) }, onClick = { open = false; onDownloads() })
        DropdownMenuItem(text = { Text(stringResource(R.string.nav_settings)) }, onClick = { open = false; onSettings() })
    }
}
