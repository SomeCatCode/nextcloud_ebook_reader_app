package com.somecatcode.ebookreader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import com.somecatcode.ebookreader.ui.util.title
import com.somecatcode.ebookreader.ui.util.serverLabel
import com.somecatcode.ebookreader.data.transfer.TransferException
import com.somecatcode.ebookreader.data.account.Account
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import com.somecatcode.ebookreader.data.repo.ShelfInfo
import com.somecatcode.ebookreader.ui.components.rememberLocalText
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
    onFilter: ((String) -> Unit)? = null,
) {
    val key = BookKey(accountId, fileId)
    val vm = containerViewModel(key = "book/$accountId/$fileId") { c ->
        BookDetailViewModel(
            key, c.libraryRepository, c.editRepository, c.downloadRepository, c.shelfRepository, c.accountStore.accounts, c.bookTransfer,
            deleteLocalBook = c.localLibrary::deleteBook,
        )
    }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val failureText = stringResource(R.string.edit_failed)
    LaunchedEffect(vm) {
        vm.failures.collect { failure -> snackbar.showSnackbar(failureText.format(failure.message)) }
    }
    val resources = LocalResources.current
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(resources.getString(it)) } }
    LaunchedEffect(vm) {
        vm.transferEvents.collect { event ->
            when (event) {
                is TransferEvent.Done -> {
                    val text = resources.getString(if (event.move) R.string.transfer_done_move else R.string.transfer_done_copy, event.targetName)
                    val extra = if (event.stateCopied) "" else " " + resources.getString(R.string.transfer_state_missing)
                    if (event.move) {
                        // the original is gone: leave its page, a toast outlives the screen
                        Toast.makeText(context, text + extra, Toast.LENGTH_LONG).show()
                        onBack()
                    } else {
                        snackbar.showSnackbar(text + extra)
                    }
                }
                is TransferEvent.Failed -> snackbar.showSnackbar(
                    if (event.reason == TransferException.Reason.NO_SPACE && event.needed != null && event.free != null) {
                        resources.getString(R.string.transfer_error_space, formatBytes(context, event.needed), formatBytes(context, event.free))
                    } else {
                        resources.getString(transferErrorText(event.reason))
                    },
                )
            }
        }
    }
    LaunchedEffect(vm) {
        vm.deleteEvents.collect { ok ->
            val title = state.book?.title.orEmpty()
            if (ok) {
                // the book is gone: leave its page, a toast outlives the screen
                Toast.makeText(context, resources.getString(R.string.book_deleted, title), Toast.LENGTH_LONG).show()
                onBack()
            } else {
                snackbar.showSnackbar(resources.getString(R.string.book_delete_failed))
            }
        }
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
        onFilter = onFilter,
        onSaveShelves = vm::saveShelves,
        onDelete = vm::deleteBook,
        transferActions = TransferActions(
            onStart = vm::startTransfer,
            onTarget = vm::selectTarget,
            onFolder = vm::selectFolder,
            onConfirm = vm::confirmTransfer,
            onDismiss = vm::dismissTransfer,
        ),
    )
}

class TransferActions(
    val onStart: (move: Boolean) -> Unit = {},
    val onTarget: (Account) -> Unit = {},
    val onFolder: (String) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

private fun transferErrorText(reason: TransferException.Reason): Int = when (reason) {
    TransferException.Reason.NOT_ALLOWED -> R.string.transfer_error_not_allowed
    TransferException.Reason.SOURCE_UNAVAILABLE -> R.string.transfer_error_source
    TransferException.Reason.TARGET_FAILED -> R.string.transfer_error_target
    TransferException.Reason.DELETE_FAILED -> R.string.transfer_error_delete
    TransferException.Reason.NO_SPACE -> R.string.transfer_error_space_unknown
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
    onFilter: ((String) -> Unit)? = null,
    onSaveShelves: (Set<Long>, String?) -> Unit = { _, _ -> },
    transferActions: TransferActions = TransferActions(),
    onDelete: (() -> Unit)? = null,
) {
    val book = state.book
    var shelfDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // only files of a local library: they are the user's own files in a folder on the device
    val canDelete = book != null && book.local && onDelete != null
    val canTransfer = book != null && book.downloadable && state.transferTargets.isNotEmpty()
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
                    if (book != null && (canTransfer || canDelete)) {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("book_menu")) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.book_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (canTransfer) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.book_copy_to)) },
                                        onClick = { menu = false; transferActions.onStart(false) },
                                        modifier = Modifier.testTag("copy_to"),
                                    )
                                    // a book shared by another user is not ours to delete
                                    if (!book.shared) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.book_move_to)) },
                                            onClick = { menu = false; transferActions.onStart(true) },
                                            modifier = Modifier.testTag("move_to"),
                                        )
                                    }
                                }
                                if (canDelete) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.book_delete)) },
                                        onClick = { menu = false; confirmDelete = true },
                                        modifier = Modifier.testTag("delete_book"),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (confirmDelete && book != null && onDelete != null) {
            com.somecatcode.ebookreader.ui.components.ConfirmDialog(
                title = stringResource(R.string.book_delete),
                text = stringResource(R.string.book_delete_confirm, book.title),
                confirmLabel = stringResource(R.string.book_delete_action),
                onConfirm = { confirmDelete = false; onDelete() },
                onDismiss = { confirmDelete = false },
            )
        }
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
                    book.authors.forEach { author ->
                        Text(
                            author,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = if (onFilter != null) Modifier.clickable { onFilter("author:$author") }.testTag("author_link") else Modifier,
                        )
                    }
                    book.series?.let { series ->
                        Text(
                            book.seriesIndex?.let { "$series #${formatIndex(it)}" } ?: series,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (onFilter != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (onFilter != null) Modifier.clickable { onFilter("series:$series") }.testTag("series_link") else Modifier,
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
            if (book.local) {
                Text(
                    stringResource(R.string.book_local_file, book.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("local_file"),
                )
            } else if (!book.downloadable) {
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

            if (book.genres.isNotEmpty()) ChipSection(stringResource(R.string.book_genres), book.genres, onFilter?.let { f -> { f("genre:$it") } })
            if (book.tags.isNotEmpty()) ChipSection(stringResource(R.string.book_tags), book.tags, onFilter?.let { f -> { f("tag:$it") } })
            // shelves are server objects: local libraries have none
            if (!book.local) Column {
                Text(stringResource(R.string.book_in_shelves), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    state.manualShelves.filter { it.key.shelfId in state.inShelves }.forEach { shelf ->
                        AssistChip(onClick = { onFilter?.invoke("shelf:${shelf.key.shelfId}") }, label = { Text(shelf.name) })
                    }
                    AssistChip(
                        onClick = { shelfDialog = true },
                        label = { Text(stringResource(R.string.shelf_add_to)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        modifier = Modifier.testTag("add_to_shelf"),
                    )
                }
            }
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

    if (shelfDialog) {
        ShelfPickerDialog(
            shelves = state.manualShelves,
            initial = state.inShelves,
            onDismiss = { shelfDialog = false },
            onConfirm = { selected, newName -> shelfDialog = false; onSaveShelves(selected, newName) },
        )
    }

    state.transfer?.let { TransferDialog(it, book, state.transferTargets, transferActions) }

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
private fun ChipSection(title: String, items: List<String>, onClick: ((String) -> Unit)?) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            items.forEach { item -> AssistChip(onClick = { onClick?.invoke(item) }, label = { Text(item) }) }
        }
    }
}

/** Target account (and library folder of a Nextcloud account) for copying or moving the book. */
@Composable
private fun TransferDialog(ui: TransferUiState, book: LibraryBook?, targets: List<Account>, actions: TransferActions) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text(stringResource(if (ui.move) R.string.transfer_title_move else R.string.transfer_title_copy)) },
        text = {
            if (ui.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text(stringResource(if (ui.move) R.string.transfer_running_move else R.string.transfer_running_copy))
                }
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.transfer_target), style = MaterialTheme.typography.labelLarge)
                targets.forEach { account ->
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.onTarget(account) }.testTag("target_${account.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ui.targetId == account.id, onClick = { actions.onTarget(account) })
                        Column {
                            Text(account.title(), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (account.local) stringResource(R.string.account_local_folder, localFolderLabel(account.serverUrl)) else account.serverLabel(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val folders = ui.folders
                if (ui.targetId != null && folders == null) {
                    CircularProgressIndicator(Modifier.padding(8.dp).width(24.dp).aspectRatio(1f), strokeWidth = 2.dp)
                } else if (folders != null && folders.size > 1) {
                    Text(stringResource(R.string.transfer_folder), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    folders.forEach { folder ->
                        Row(Modifier.fillMaxWidth().clickable { actions.onFolder(folder) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = ui.folder == folder, onClick = { actions.onFolder(folder) })
                            Text(folder, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (ui.move && book != null) {
                    Text(
                        stringResource(if (book.local) R.string.transfer_move_hint_local else R.string.transfer_move_hint_cloud),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (!ui.running) {
                TextButton(
                    onClick = actions.onConfirm,
                    enabled = ui.targetId != null && ui.folders != null,
                    modifier = Modifier.testTag("transfer_confirm"),
                ) { Text(stringResource(if (ui.move) R.string.transfer_move else R.string.transfer_copy)) }
            }
        },
        dismissButton = {
            if (!ui.running) TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Manual shelves with checkboxes plus a field for a new shelf. */
@Composable
private fun ShelfPickerDialog(
    shelves: List<ShelfInfo>,
    initial: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>, String?) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelf_add_dialog_title)) },
        text = {
            Column {
                if (shelves.isEmpty()) Text(stringResource(R.string.shelf_none_manual), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shelves, key = { it.key.shelfId }) { shelf ->
                        val checked = shelf.key.shelfId in selected
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { selected = if (checked) selected - shelf.key.shelfId else selected + shelf.key.shelfId }
                                .padding(vertical = 4.dp)
                                .testTag("pick_shelf_${shelf.key.shelfId}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(shelf.name, Modifier.padding(start = 12.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { if (it.length <= 255) newName = it },
                    label = { Text(stringResource(R.string.shelf_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("new_shelf_field"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, newName.trim().ifEmpty { null }) }, modifier = Modifier.testTag("shelves_save")) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
    val title = rememberLocalText(form.title) { v -> onChange { it.copy(title = v) } }
    val authors = rememberLocalText(form.authors) { v -> onChange { it.copy(authors = v) } }
    val series = rememberLocalText(form.series) { v -> onChange { it.copy(series = v) } }
    val seriesIndex = rememberLocalText(form.seriesIndex) { v -> onChange { it.copy(seriesIndex = v) } }
    val genres = rememberLocalText(form.genres) { v -> onChange { it.copy(genres = v) } }
    val tags = rememberLocalText(form.tags) { v -> onChange { it.copy(tags = v) } }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.book_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title.text, title.onChange,
                    label = { Text(stringResource(R.string.edit_title)) },
                    isError = error == EditError.TITLE_EMPTY,
                    supportingText = if (error == EditError.TITLE_EMPTY) ({ Text(stringResource(R.string.edit_error_title_empty)) }) else null,
                    modifier = Modifier.fillMaxWidth().testTag("edit_title"),
                )
                OutlinedTextField(
                    authors.text, authors.onChange,
                    label = { Text(stringResource(R.string.edit_authors)) },
                    supportingText = { Text(stringResource(R.string.edit_comma_separated)) },
                    modifier = Modifier.fillMaxWidth().testTag("edit_authors"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        series.text, series.onChange,
                        label = { Text(stringResource(R.string.edit_series)) },
                        modifier = Modifier.weight(2f).testTag("edit_series"),
                    )
                    OutlinedTextField(
                        seriesIndex.text, seriesIndex.onChange,
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
                    genres.text, genres.onChange,
                    label = { Text(stringResource(R.string.book_genres)) },
                    supportingText = { Text(stringResource(R.string.edit_comma_separated)) },
                    modifier = Modifier.fillMaxWidth().testTag("edit_genres"),
                )
                Suggestions(genreSuggestions, form.genres) { name -> onChange { it.copy(genres = appendToList(it.genres, name)) } }
                OutlinedTextField(
                    tags.text, tags.onChange,
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
