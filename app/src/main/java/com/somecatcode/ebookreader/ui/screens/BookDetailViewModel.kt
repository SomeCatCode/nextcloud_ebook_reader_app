package com.somecatcode.ebookreader.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.data.api.AppDataPatch
import com.somecatcode.ebookreader.data.api.MetadataPatch
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.EditFailure
import com.somecatcode.ebookreader.data.repo.EditRepository
import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.data.repo.LibraryRepository
import com.somecatcode.ebookreader.data.repo.OfflineTarget
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.data.repo.ShelfKey
import com.somecatcode.ebookreader.data.repo.ShelfRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Text form for the metadata editor; lists are comma separated. */
data class BookEditForm(
    val title: String,
    val authors: String,
    val series: String,
    val seriesIndex: String,
    val genres: String,
    val tags: String,
) {
    companion object {
        fun from(book: LibraryBook) = BookEditForm(
            title = book.title,
            authors = book.authors.joinToString(", "),
            series = book.series.orEmpty(),
            seriesIndex = book.seriesIndex?.let(::formatIndex).orEmpty(),
            genres = book.genres.joinToString(", "),
            tags = book.tags.joinToString(", "),
        )
    }
}

enum class EditError { TITLE_EMPTY, INDEX_INVALID }

internal fun parseList(text: String): List<String> = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

internal fun parseIndex(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** Adds [value] to a comma separated list; a partially typed last fragment that matches it is replaced. */
internal fun appendToList(text: String, value: String): String {
    val items = parseList(text).toMutableList()
    val fragment = text.substringAfterLast(',').trim()
    if (fragment.equals(value, ignoreCase = true)) return items.joinToString(", ")
    if (fragment.isNotEmpty() && items.isNotEmpty() && value.contains(fragment, ignoreCase = true)) items.removeAt(items.lastIndex)
    if (items.none { it.equals(value, ignoreCase = true) }) items.add(value)
    return items.joinToString(", ")
}

internal fun validateEdit(form: BookEditForm): EditError? = when {
    form.title.isBlank() -> EditError.TITLE_EMPTY
    form.seriesIndex.isNotBlank() && parseIndex(form.seriesIndex) == null -> EditError.INDEX_INVALID
    else -> null
}

/**
 * Builds the `PATCH /books/{id}/metadata` body from the changed fields only (`JsonNull` clears a
 * field). Returns null when nothing changed. Call [validateEdit] first.
 */
internal fun buildMetadataPatch(book: LibraryBook, form: BookEditForm): MetadataPatch? {
    val fields = linkedMapOf<String, JsonElement>()
    val title = form.title.trim()
    if (title != book.title) fields["title"] = JsonPrimitive(title)
    val authors = parseList(form.authors)
    if (authors != book.authors) fields["authors"] = JsonArray(authors.map(::JsonPrimitive))
    val series = form.series.trim().ifEmpty { null }
    if (series != book.series) fields["series"] = series?.let(::JsonPrimitive) ?: JsonNull
    val index = parseIndex(form.seriesIndex)
    if (index != book.seriesIndex) fields["seriesIndex"] = index?.let(::JsonPrimitive) ?: JsonNull
    val genres = parseList(form.genres)
    if (genres != book.genres) fields["genres"] = JsonArray(genres.map(::JsonPrimitive))
    val tags = parseList(form.tags)
    if (tags != book.tags) fields["tags"] = JsonArray(tags.map(::JsonPrimitive))
    return if (fields.isEmpty()) null else MetadataPatch(fields)
}

data class BookDetailUiState(
    val loaded: Boolean = false,
    val book: LibraryBook? = null,
    val editing: BookEditForm? = null,
    val editError: EditError? = null,
    val genreSuggestions: List<FacetCount> = emptyList(),
    val tagSuggestions: List<FacetCount> = emptyList(),
    /** Manual shelves of the book's account (books can only be added to those). */
    val manualShelves: List<ShelfInfo> = emptyList(),
    /** Ids of the manual shelves containing the book. */
    val inShelves: Set<Long> = emptySet(),
)

class BookDetailViewModel(
    val key: BookKey,
    library: LibraryRepository,
    private val edits: EditRepository,
    private val downloads: DownloadRepository,
    private val shelves: ShelfRepository? = null,
) : ViewModel() {

    private val editing = MutableStateFlow<BookEditForm?>(null)
    private val editError = MutableStateFlow<EditError?>(null)

    private val shelfState: Flow<Pair<List<ShelfInfo>, Set<Long>>> = combine(
        library.shelves(listOf(key.accountId)).map { list -> list.filter { !it.smart } },
        shelves?.shelvesOf(key) ?: flowOf(emptySet()),
    ) { list, member -> list to member }

    val state: StateFlow<BookDetailUiState> = combine(
        library.book(key), editing, editError, library.genres(listOf(key.accountId)), library.tags(listOf(key.accountId)),
    ) { book, form, error, genres, tags ->
        BookDetailUiState(true, book, form, error, genres, tags)
    }.combine(shelfState) { st, (list, member) -> st.copy(manualShelves = list, inShelves = member) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState())

    private val messageChannel = Channel<Int>(Channel.BUFFERED)

    /** Shelf errors (string resource ids) for a snackbar. */
    val messages: Flow<Int> = messageChannel.receiveAsFlow()

    /** Applies the shelf selection of the dialog; [newShelf] creates a manual shelf containing the book. */
    fun saveShelves(selected: Set<Long>, newShelf: String?) {
        val repo = shelves ?: return
        val before = state.value.inShelves
        viewModelScope.launch {
            try {
                newShelf?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    repo.addBooks(repo.create(key.accountId, name), listOf(key.fileId))
                }
                for (id in selected - before) repo.addBooks(ShelfKey(key.accountId, id), listOf(key.fileId))
                for (id in before - selected) repo.removeBooks(ShelfKey(key.accountId, id), listOf(key.fileId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(shelfErrorMessage(e))
            }
        }
    }

    /** Edits the server rejected for this book (shown as a snackbar). */
    val failures: Flow<EditFailure> = edits.failures.filter { it.key == key }

    fun startEdit() {
        val book = state.value.book ?: return
        if (!book.editable) return
        editError.value = null
        editing.value = BookEditForm.from(book)
    }

    fun updateForm(transform: (BookEditForm) -> BookEditForm) {
        editing.update { it?.let(transform) }
        editError.value = null
    }

    fun cancelEdit() {
        editing.value = null
        editError.value = null
    }

    fun saveEdit() {
        val form = editing.value ?: return
        val book = state.value.book ?: return
        validateEdit(form)?.let { editError.value = it; return }
        val patch = buildMetadataPatch(book, form)
        editing.value = null
        if (patch != null) viewModelScope.launch { edits.editMetadata(key, patch) }
    }

    fun setRating(rating: Int?) {
        val book = state.value.book ?: return
        if (rating == book.rating) return
        viewModelScope.launch { edits.editAppData(key, AppDataPatch(setRating = true, rating = rating)) }
    }

    fun setStatus(status: ReadStatus) {
        val book = state.value.book ?: return
        if (status == book.readStatus) return
        viewModelScope.launch { edits.editAppData(key, AppDataPatch(readStatus = status)) }
    }

    fun setOffline(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) downloads.makeAvailableOffline(OfflineTarget.Book(key)) else downloads.removeOffline(OfflineTarget.Book(key))
        }
    }

    fun retryDownload() {
        viewModelScope.launch { downloads.retry(key) }
    }
}
