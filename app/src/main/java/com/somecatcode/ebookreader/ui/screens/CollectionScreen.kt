package com.somecatcode.ebookreader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibraryFilter
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.LibrarySort
import com.somecatcode.ebookreader.data.repo.OfflineTarget
import com.somecatcode.ebookreader.data.repo.SettingsRepository
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.data.repo.ShelfKey
import com.somecatcode.ebookreader.data.repo.ShelfRepository
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.components.BookCover
import com.somecatcode.ebookreader.ui.components.ConfirmDialog
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.components.OfflineIndicator
import com.somecatcode.ebookreader.ui.components.rememberDownloadGate
import com.somecatcode.ebookreader.ui.util.containerViewModel
import com.somecatcode.ebookreader.ui.util.percentText
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
import kotlinx.coroutines.launch

/** A shelf or a series. */
sealed interface CollectionId {
    val accountId: String

    data class Shelf(override val accountId: String, val shelfId: Long) : CollectionId
    data class Series(override val accountId: String, val name: String) : CollectionId
}

data class CollectionUiState(
    val loaded: Boolean = false,
    val title: String = "",
    val books: List<LibraryBook> = emptyList(),
    /** Every book of the collection is queued, downloading or available offline. */
    val allOffline: Boolean = false,
    /** Manual shelf: books can be removed. */
    val manualShelf: Boolean = false,
    /** Smart shelf: its filter can be edited in the library. */
    val smartShelf: Boolean = false,
    val refreshing: Boolean = false,
    /** Grid or list; shared with the library (setting `libraryLayout`). */
    val grid: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModel(
    private val id: CollectionId,
    library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val shelves: ShelfRepository? = null,
    private val settings: SettingsRepository? = null,
) : ViewModel() {

    private val ids = listOf(id.accountId)
    private val refreshing = MutableStateFlow(false)
    private val messageChannel = Channel<Int>(Channel.BUFFERED)
    val messages: Flow<Int> = messageChannel.receiveAsFlow()

    private val shelfInfo: Flow<ShelfInfo?> = when (id) {
        is CollectionId.Shelf -> library.shelves(ids).map { list -> list.firstOrNull { it.key.shelfId == id.shelfId } }
        is CollectionId.Series -> flowOf(null)
    }

    private val booksFlow: Flow<List<LibraryBook>> = when (id) {
        is CollectionId.Shelf -> shelfInfo.map { it?.smart == true }.distinctUntilChanged().flatMapLatest { smart ->
            val q = shelfInfo.first()?.query
            val sort = if (smart) q?.let { sortFromWire(it.sort) } ?: LibrarySort.TITLE else LibrarySort.SHELF
            val desc = if (smart) q?.order == "desc" else false
            library.books(ids, LibraryFilter(shelf = ShelfKey(id.accountId, id.shelfId), sort = sort, descending = desc))
        }
        is CollectionId.Series -> library.books(ids, LibraryFilter(series = id.name, sort = LibrarySort.SERIES))
    }

    private val grid: Flow<Boolean> = settings?.settings?.map { it.libraryLayout != "list" }?.distinctUntilChanged() ?: flowOf(true)

    val state: StateFlow<CollectionUiState> = combine(booksFlow, shelfInfo, refreshing, grid) { books, shelf, busy, grid ->
        CollectionUiState(
            loaded = true,
            title = if (id is CollectionId.Series) id.name else shelf?.name.orEmpty(),
            books = books,
            allOffline = books.isNotEmpty() && books.all { it.offline.state != null },
            manualShelf = shelf != null && !shelf.smart,
            smartShelf = shelf?.smart == true,
            refreshing = busy,
            grid = grid,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState())

    private val target: OfflineTarget = when (id) {
        is CollectionId.Shelf -> OfflineTarget.Shelf(ShelfKey(id.accountId, id.shelfId))
        is CollectionId.Series -> OfflineTarget.Series(id.accountId, id.name)
    }

    init {
        refresh()
    }

    /** Membership changes on the server do not always reach the last sync; reload the shelf when it is opened. */
    fun refresh() {
        val repo = shelves ?: return
        if (id !is CollectionId.Shelf) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                repo.refreshMembers(ShelfKey(id.accountId, id.shelfId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(R.string.shelf_refresh_failed)
            } finally {
                refreshing.value = false
            }
        }
    }

    fun toggleLayout() {
        val repo = settings ?: return
        viewModelScope.launch { repo.update { it.copy(libraryLayout = if (it.libraryLayout == "list") "grid" else "list") } }
    }

    fun setOffline(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) downloads.makeAvailableOffline(target) else downloads.removeOffline(target)
        }
    }

    fun removeFromShelf(book: BookKey) {
        val repo = shelves ?: return
        if (id !is CollectionId.Shelf) return
        viewModelScope.launch {
            try {
                repo.removeBooks(ShelfKey(id.accountId, id.shelfId), listOf(book.fileId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(shelfErrorMessage(e))
            }
        }
    }
}

/** Shelf or series detail with "Make available offline" for the whole collection. */
@Composable
fun CollectionScreen(
    id: CollectionId,
    onBack: () -> Unit,
    onOpenBook: (accountId: String, fileId: Long) -> Unit,
    onEditSmartShelf: (accountId: String, shelfId: Long) -> Unit = { _, _ -> },
) {
    val vm = containerViewModel(key = "collection/$id") { c -> CollectionViewModel(id, c.libraryRepository, c.downloadRepository, c.shelfRepository, c.settingsRepository) }
    val state by vm.state.collectAsState()
    val gate = rememberDownloadGate()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(resources.getString(it)) } }
    CollectionContent(
        state = state,
        isSeries = id is CollectionId.Series,
        onBack = onBack,
        onToggleOffline = { enabled -> if (enabled) gate { vm.setOffline(true) } else vm.setOffline(false) },
        onOpenBook = { onOpenBook(it.accountId, it.fileId) },
        onRemoveBook = vm::removeFromShelf,
        onEditSmart = { if (id is CollectionId.Shelf) onEditSmartShelf(id.accountId, id.shelfId) },
        onToggleLayout = vm::toggleLayout,
        snackbar = snackbar,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionContent(
    state: CollectionUiState,
    isSeries: Boolean,
    onBack: () -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onOpenBook: (BookKey) -> Unit,
    onRemoveBook: (BookKey) -> Unit = {},
    onEditSmart: () -> Unit = {},
    onToggleLayout: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var removing by remember { mutableStateOf<LibraryBook?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { stringResource(if (isSeries) R.string.library_tab_series else R.string.library_tab_shelves) }) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onToggleLayout, modifier = Modifier.testTag("collection_layout_toggle")) {
                        Icon(
                            if (state.grid) Icons.Filled.ViewList else Icons.Filled.GridView,
                            contentDescription = stringResource(if (state.grid) R.string.library_layout_list else R.string.library_layout_grid),
                        )
                    }
                    if (state.smartShelf) {
                        IconButton(onClick = onEditSmart, modifier = Modifier.testTag("edit_smart")) {
                            Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.shelf_open_as_filter))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        removing?.let { book ->
            ConfirmDialog(
                title = stringResource(R.string.shelf_remove_book),
                text = stringResource(R.string.shelf_remove_book_confirm, book.title),
                confirmLabel = stringResource(R.string.action_remove),
                onConfirm = { removing = null; onRemoveBook(book.key) },
                onDismiss = { removing = null },
            )
        }
        if (state.loaded && state.books.isEmpty()) {
            EmptyState(stringResource(R.string.collection_empty), Modifier.padding(padding))
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = if (state.grid) GridCells.Adaptive(120.dp) else GridCells.Fixed(1),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (state.grid) 12.dp else 4.dp),
            modifier = Modifier.fillMaxSize().testTag(if (state.grid) "collection_grid" else "collection_list"),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    Text(pluralStringResource(R.plurals.books_count, state.books.size, state.books.size), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (isSeries) R.string.collection_offline_series else R.string.collection_offline_shelf),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(checked = state.allOffline, onCheckedChange = onToggleOffline, modifier = Modifier.testTag("collection_offline_switch"))
                    }
                }
            }
            items(state.books, key = { "${it.key.accountId}/${it.key.fileId}" }) { book ->
                val title = (if (isSeries) book.seriesIndex?.let { "#${formatIndex(it)} " }.orEmpty() else "") + book.title
                val click = Modifier
                    .combinedClickable(
                        onClick = { onOpenBook(book.key) },
                        onLongClick = if (state.manualShelf) ({ removing = book }) else null,
                        onLongClickLabel = if (state.manualShelf) stringResource(R.string.shelf_remove_book) else null,
                    )
                    .testTag("book_${book.key.fileId}")
                if (state.grid) {
                    Column(click) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
                            BookCover(book, Modifier.fillMaxSize())
                            OfflineIndicator(book.offline, Modifier.align(Alignment.TopEnd).padding(4.dp))
                            book.percentage?.takeIf { it > 0.0 && it < 1.0 }?.let {
                                LinearProgressIndicator({ it.toFloat() }, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                            }
                        }
                        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        ReadingStateLabel(book, Modifier.padding(top = 2.dp))
                    }
                } else {
                    Row(click.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookCover(book, Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(4.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (book.authors.isNotEmpty()) {
                                Text(
                                    book.authors.joinToString(), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ReadingStateLabel(book, Modifier.padding(top = 2.dp))
                            book.percentage?.takeIf { it > 0.0 && it < 1.0 }?.let {
                                LinearProgressIndicator({ it.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                        OfflineIndicator(book.offline)
                    }
                }
            }
        }
    }
}

/** "Gelesen" with a check mark, the progress in percent while reading, otherwise "Ungelesen". */
@Composable
internal fun ReadingStateLabel(book: LibraryBook, modifier: Modifier = Modifier) {
    val percentage = book.percentage
    val finished = book.readStatus == ReadStatus.FINISHED
    Row(modifier.testTag("reading_state_${book.key.fileId}"), verticalAlignment = Alignment.CenterVertically) {
        if (finished) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp).size(14.dp),
            )
        }
        Text(
            when {
                finished -> stringResource(R.string.status_finished)
                percentage != null && percentage > 0.0 -> percentText(percentage)
                book.readStatus == ReadStatus.READING -> stringResource(R.string.status_reading)
                else -> stringResource(R.string.status_unread)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (finished || (percentage ?: 0.0) > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
