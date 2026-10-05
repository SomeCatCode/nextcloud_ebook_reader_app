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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibraryFilter
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.LibrarySort
import com.somecatcode.ebookreader.data.repo.OfflineTarget
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.data.repo.ShelfKey
import com.somecatcode.ebookreader.data.repo.ShelfRepository
import com.somecatcode.ebookreader.ui.components.ConfirmDialog
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.components.BookCover
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.components.OfflineIndicator
import com.somecatcode.ebookreader.ui.components.rememberDownloadGate
import com.somecatcode.ebookreader.ui.util.containerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
)

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModel(
    private val id: CollectionId,
    library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val shelves: ShelfRepository? = null,
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

    val state: StateFlow<CollectionUiState> = combine(booksFlow, shelfInfo, refreshing) { books, shelf, busy ->
        CollectionUiState(
            loaded = true,
            title = if (id is CollectionId.Series) id.name else shelf?.name.orEmpty(),
            books = books,
            allOffline = books.isNotEmpty() && books.all { it.offline.state != null },
            manualShelf = shelf != null && !shelf.smart,
            smartShelf = shelf?.smart == true,
            refreshing = busy,
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
    val vm = containerViewModel(key = "collection/$id") { c -> CollectionViewModel(id, c.libraryRepository, c.downloadRepository, c.shelfRepository) }
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
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var removing by remember { mutableStateOf<LibraryBook?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { stringResource(if (isSeries) R.string.library_tab_series else R.string.library_tab_shelves) }) },
                navigationIcon = { BackButton(onBack) },
                actions = {
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
            columns = GridCells.Adaptive(120.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
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
                Column(
                    Modifier
                        .combinedClickable(
                            onClick = { onOpenBook(book.key) },
                            onLongClick = if (state.manualShelf) ({ removing = book }) else null,
                            onLongClickLabel = if (state.manualShelf) stringResource(R.string.shelf_remove_book) else null,
                        )
                        .testTag("book_${book.key.fileId}"),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
                        BookCover(book, Modifier.fillMaxSize())
                        OfflineIndicator(book.offline, Modifier.align(Alignment.TopEnd).padding(4.dp))
                        book.percentage?.takeIf { it > 0.0 && it < 1.0 }?.let {
                            LinearProgressIndicator({ it.toFloat() }, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        }
                    }
                    Text(
                        (if (isSeries) book.seriesIndex?.let { "#${formatIndex(it)} " }.orEmpty() else "") + book.title,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
