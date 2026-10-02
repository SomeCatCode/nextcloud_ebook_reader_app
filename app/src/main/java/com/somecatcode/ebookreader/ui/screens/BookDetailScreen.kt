package com.somecatcode.ebookreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.api.ReadStatus
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.FacetCount
import com.somecatcode.ebookreader.data.repo.LibraryBook
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.components.BookCover
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.components.StarRating
import com.somecatcode.ebookreader.ui.components.rememberDownloadGate
import com.somecatcode.ebookreader.ui.util.containerViewModel
import com.somecatcode.ebookreader.ui.util.formatBytes
import com.somecatcode.ebookreader.ui.util.percentText

/** Book details, offline toggle, minimal metadata editing. */
@Composable
fun BookDetailScreen(
    accountId: String,
    fileId: Long,
    onBack: () -> Unit,
    onRead: () -> Unit,
    showBack: Boolean = true,
) {
    val key = BookKey(accountId, fileId)
    val vm = containerViewModel(key = "book/$accountId/$fileId") { c ->
        BookDetailViewModel(key, c.libraryRepository, c.editRepository, c.downloadRepository)
    }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val failureText = stringResource(R.string.edit_failed)
    LaunchedEffect(vm) {
        vm.failures.collect { failure -> snackbar.showSnackbar(failureText.format(failure.message)) }
    }
    val gate = rememberDownloadGate()
    BookDetailContent(
        state = state,
        snackbar = snackbar,
        showBack = showBack,
        onBack = onBack,
        onRead = onRead,
        onToggleOffline = { enabled -> if (enabled) gate { vm.setOffline(true) } else vm.setOffline(false) },
        onRetryDownload = vm::retryDownload,
        onRating = vm::setRating,
        onStatus = vm::setStatus,
        onStartEdit = vm::startEdit,
        onFormChange = vm::updateForm,
        onSaveEdit = vm::saveEdit,
        onCancelEdit = vm::cancelEdit,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailContent(
    state: BookDetailUiState,
    snackbar: SnackbarHostState,
    showBack: Boolean,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onRetryDownload: () -> Unit,
    onRating: (Int?) -> Unit,
    onStatus: (ReadStatus) -> Unit,
    onStartEdit: () -> Unit,
    onFormChange: ((BookEditForm) -> BookEditForm) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val book = state.book
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_book_detail)) },
                navigationIcon = { if (showBack) BackButton(onBack) },
                actions = {
                    if (book?.editable == true) {
                        IconButton(onClick = onStartEdit, modifier = Modifier.testTag("edit_book")) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.book_edit))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (book == null) {
            if (state.loaded) EmptyState(stringResource(R.string.book_not_found), Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(book, Modifier.width(140.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)), large = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("book_title"))
                    if (book.authors.isNotEmpty()) Text(book.authors.joinToString(), style = MaterialTheme.typography.titleMedium)
                    book.series?.let { series ->
                        Text(
                            book.seriesIndex?.let { "$series #${formatIndex(it)}" } ?: series,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val context = LocalContext.current
                    Text(
                        "${book.format.uppercase()} · ${formatBytes(context, book.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (book.hasPendingEdit) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("pending_edit")) {
                            CircularProgressIndicator(Modifier.width(16.dp).aspectRatio(1f), strokeWidth = 2.dp)
                            Text(stringResource(R.string.edit_pending), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Progress and read button.
            val percentage = book.percentage
            if (percentage != null && percentage > 0.0) {
                Column {
                    Text(stringResource(R.string.book_progress, percentText(percentage)), style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator({ percentage.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            Button(onClick = onRead, enabled = book.downloadable, modifier = Modifier.fillMaxWidth().testTag("read_button")) {
                Text(
                    stringResource(
                        when {
                            book.offline.isAvailableOffline -> R.string.book_read_offline
                            else -> R.string.book_read_online
                        },
                    ),
                )
            }
            if (!book.downloadable) {
                Text(stringResource(R.string.book_view_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OfflineRow(book, onToggleOffline, onRetryDownload)
            }

            // Rating and status (always stored in the app database of the server).
            Column {
                Text(stringResource(R.string.book_rating), style = MaterialTheme.typography.labelLarge)
                StarRating(book.rating, onRating, Modifier.testTag("rating"))
            }
            Column {
                Text(stringResource(R.string.book_status), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadStatus.entries.forEach { status ->
                        FilterChip(
                            selected = book.readStatus == status,
                            onClick = { onStatus(status) },
                            label = { Text(stringResource(statusTitle(status))) },
                            modifier = Modifier.testTag("read_status_${status.name.lowercase()}"),
                        )
                    }
                }
            }

            if (book.genres.isNotEmpty()) ChipSection(stringResource(R.string.book_genres), book.genres)
            if (book.tags.isNotEmpty()) ChipSection(stringResource(R.string.book_tags), book.tags)
            book.descriptionHtml?.takeIf { it.isNotBlank() }?.let { html ->
                val text = remember(html) { HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }
                Column {
                    Text(stringResource(R.string.book_description), style = MaterialTheme.typography.labelLarge)
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
            val details = listOfNotNull(
                book.publisher?.let { stringResource(R.string.book_publisher) to it },
                book.language?.let { stringResource(R.string.book_language) to it },
                book.isbn?.let { stringResource(R.string.book_isbn) to it },
            )
            details.forEach { (label, value) ->
                Row {
                    Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    state.editing?.let { form ->
        EditBookDialog(form, state.editError, state.genreSuggestions, state.tagSuggestions, onFormChange, onSaveEdit, onCancelEdit)
    }
}

@Composable
private fun OfflineRow(book: LibraryBook, onToggle: (Boolean) -> Unit, onRetry: () -> Unit) {
    val offline = book.offline
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.book_available_offline), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = offline.state != null, onCheckedChange = onToggle, modifier = Modifier.testTag("offline_switch"))
        }
        when (offline.state) {
            DownloadState.QUEUED -> Text(stringResource(R.string.download_queued), style = MaterialTheme.typography.bodySmall)
            DownloadState.RUNNING -> {
                val fraction = if (offline.total > 0) (offline.bytes.toFloat() / offline.total).coerceIn(0f, 1f) else null
                if (fraction != null) LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.download_in_progress), style = MaterialTheme.typography.bodySmall)
            }
            DownloadState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.download_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(title: String, items: List<String>) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditBookDialog(
    form: BookEditForm,
    error: EditError?,
    genreSuggestions: List<FacetCount>,
    tagSuggestions: List<FacetCount>,
    onChange: ((BookEditForm) -> BookEditForm) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.book_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.title, { v -> onChange { it.copy(title = v) } },
                    label = { Text(stringResource(R.string.edit_title)) },
                    isError = error == EditError.TITLE_EMPTY,
                    supportingText = if (error == EditError.TITLE_EMPTY) ({ Text(stringResource(R.string.edit_error_title_empty)) }) else null,
                    modifier = Modifier.fillMaxWidth().testTag("edit_title"),
                )
                OutlinedTextField(
                    form.authors, { v -> onChange { it.copy(authors = v) } },
                    label = { Text(stringResource(R.string.edit_authors)) },
                    supportingText = { Text(stringResource(R.string.edit_comma_separated)) },
                    modifier = Modifier.fillMaxWidth().testTag("edit_authors"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        form.series, { v -> onChange { it.copy(series = v) } },
                        label = { Text(stringResource(R.string.edit_series)) },
                        modifier = Modifier.weight(2f).testTag("edit_series"),
                    )
                    OutlinedTextField(
                        form.seriesIndex, { v -> onChange { it.copy(seriesIndex = v) } },
                        label = { Text(stringResource(R.string.edit_series_index)) },
                        isError = error == EditError.INDEX_INVALID,
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("edit_series_index"),
                    )
                }
                if (error == EditError.INDEX_INVALID) {
                    Text(stringResource(R.string.edit_error_index_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    form.genres, { v -> onChange { it.copy(genres = v) } },
                    label = { Text(stringResource(R.string.book_genres)) },
                    supportingText = { Text(stringResource(R.string.edit_comma_separated)) },
                    modifier = Modifier.fillMaxWidth().testTag("edit_genres"),
                )
                Suggestions(genreSuggestions, form.genres) { name -> onChange { it.copy(genres = appendToList(it.genres, name)) } }
                OutlinedTextField(
                    form.tags, { v -> onChange { it.copy(tags = v) } },
                    label = { Text(stringResource(R.string.book_tags)) },
                    supportingText = { Text(stringResource(R.string.edit_comma_separated)) },
                    modifier = Modifier.fillMaxWidth().testTag("edit_tags"),
                )
                Suggestions(tagSuggestions, form.tags) { name -> onChange { it.copy(tags = appendToList(it.tags, name)) } }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, modifier = Modifier.testTag("edit_save")) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Up to eight facet suggestions that are not yet in the field (filtered by the last typed fragment). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(all: List<FacetCount>, current: String, onPick: (String) -> Unit) {
    val used = parseList(current).map { it.lowercase() }.toSet()
    val fragment = current.substringAfterLast(',').trim().lowercase()
    val shown = all.filter { it.name.lowercase() !in used && (fragment.isEmpty() || it.name.lowercase().contains(fragment)) }.take(8)
    if (shown.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { facet ->
            SuggestionChip(onClick = { onPick(facet.name) }, label = { Text(facet.name) })
        }
    }
}
