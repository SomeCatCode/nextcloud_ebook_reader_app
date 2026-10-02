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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.somecatcode.ebookreader.data.repo.ShelfKey
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.components.BookCover
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.components.OfflineIndicator
import com.somecatcode.ebookreader.ui.components.rememberDownloadGate
import com.somecatcode.ebookreader.ui.util.containerViewModel
import kotlinx.coroutines.flow.Flow
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
)

class CollectionViewModel(
    private val id: CollectionId,
    library: LibraryRepository,
    private val downloads: DownloadRepository,
) : ViewModel() {

    private val ids = listOf(id.accountId)

    private val booksFlow: Flow<List<LibraryBook>> = when (id) {
        is CollectionId.Shelf -> library.books(ids, LibraryFilter(shelf = ShelfKey(id.accountId, id.shelfId), sort = LibrarySort.TITLE))
        is CollectionId.Series -> library.books(ids, LibraryFilter(series = id.name, sort = LibrarySort.SERIES))
    }

    private val titleFlow: Flow<String> = when (id) {
        is CollectionId.Shelf -> library.shelves(ids).map { list -> list.firstOrNull { it.key.shelfId == id.shelfId }?.name.orEmpty() }
        is CollectionId.Series -> kotlinx.coroutines.flow.flowOf(id.name)
    }

    val state: StateFlow<CollectionUiState> = combine(booksFlow, titleFlow) { books, title ->
        CollectionUiState(true, title, books, books.isNotEmpty() && books.all { it.offline.state != null })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState())

    private val target: OfflineTarget = when (id) {
        is CollectionId.Shelf -> OfflineTarget.Shelf(ShelfKey(id.accountId, id.shelfId))
        is CollectionId.Series -> OfflineTarget.Series(id.accountId, id.name)
    }

    fun setOffline(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) downloads.makeAvailableOffline(target) else downloads.removeOffline(target)
        }
    }
}

/** Shelf or series detail with "Make available offline" for the whole collection. */
@Composable
fun CollectionScreen(id: CollectionId, onBack: () -> Unit, onOpenBook: (accountId: String, fileId: Long) -> Unit) {
    val vm = containerViewModel(key = "collection/$id") { c -> CollectionViewModel(id, c.libraryRepository, c.downloadRepository) }
    val state by vm.state.collectAsState()
    val gate = rememberDownloadGate()
    CollectionContent(
        state = state,
        isSeries = id is CollectionId.Series,
        onBack = onBack,
        onToggleOffline = { enabled -> if (enabled) gate { vm.setOffline(true) } else vm.setOffline(false) },
        onOpenBook = { onOpenBook(it.accountId, it.fileId) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionContent(
    state: CollectionUiState,
    isSeries: Boolean,
    onBack: () -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onOpenBook: (BookKey) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(state.title.ifBlank { stringResource(if (isSeries) R.string.library_tab_series else R.string.library_tab_shelves) }) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
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
                Column(Modifier.clickable { onOpenBook(book.key) }.testTag("book_${book.key.fileId}")) {
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
