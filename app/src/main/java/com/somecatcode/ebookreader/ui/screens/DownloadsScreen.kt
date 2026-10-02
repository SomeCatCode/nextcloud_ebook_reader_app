package com.somecatcode.ebookreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.DownloadRepository
import com.somecatcode.ebookreader.data.repo.OfflineItem
import com.somecatcode.ebookreader.data.repo.OfflineTarget
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.components.ConfirmDialog
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.util.containerViewModel
import com.somecatcode.ebookreader.ui.util.formatBytes
import com.somecatcode.ebookreader.ui.util.title
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountDownloads(val accountId: String, val accountName: String, val items: List<OfflineItem>)

data class DownloadsUiState(
    val loaded: Boolean = false,
    val groups: List<AccountDownloads> = emptyList(),
    val usedBytes: Long = 0,
)

class DownloadsViewModel(
    accountStore: AccountStore,
    private val downloads: DownloadRepository,
) : ViewModel() {

    val state: StateFlow<DownloadsUiState> = combine(downloads.items, downloads.usedBytes, accountStore.accounts) { items, used, accounts ->
        val names = accounts.associate { it.id to it.title() }
        val groups = items.groupBy { it.key.accountId }
            .map { (accountId, list) -> AccountDownloads(accountId, names[accountId] ?: accountId, list.sortedBy { it.title.lowercase() }) }
            .sortedBy { it.accountName.lowercase() }
        DownloadsUiState(true, groups, used)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun cancel(key: BookKey) {
        viewModelScope.launch { downloads.cancel(key) }
    }

    fun retry(key: BookKey) {
        viewModelScope.launch { downloads.retry(key) }
    }

    fun remove(key: BookKey) {
        viewModelScope.launch { downloads.removeOffline(OfflineTarget.Book(key)) }
    }

    /** Removes every offline book of one account (library data stays). */
    fun removeAllForAccount(accountId: String) {
        val keys = state.value.groups.firstOrNull { it.accountId == accountId }?.items?.map { it.key }.orEmpty()
        viewModelScope.launch {
            keys.forEach { key ->
                downloads.cancel(key)
                downloads.removeOffline(OfflineTarget.Book(key))
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch { downloads.clearAll() }
    }
}

/** Downloads and storage management. */
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { c -> DownloadsViewModel(c.accountStore, c.downloadRepository) }
    val state by vm.state.collectAsState()
    DownloadsContent(state, onBack, vm::cancel, vm::retry, vm::remove, vm::removeAllForAccount, vm::clearAll)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onCancel: (BookKey) -> Unit,
    onRetry: (BookKey) -> Unit,
    onRemove: (BookKey) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    var confirmAccount by remember { mutableStateOf<AccountDownloads?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_downloads)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (state.groups.isNotEmpty()) {
                        TextButton(onClick = { confirmAll = true }, modifier = Modifier.testTag("clear_all")) {
                            Text(stringResource(R.string.downloads_remove_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loaded && state.groups.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.downloads_empty_title),
                body = stringResource(R.string.downloads_empty_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.downloads_storage_used, formatBytes(context, state.usedBytes)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp).testTag("storage_used"),
                )
            }
            state.groups.forEach { group ->
                item(key = "h_${group.accountId}") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.accountName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { confirmAccount = group }) { Text(stringResource(R.string.downloads_remove_account)) }
                    }
                }
                items(group.items, key = { "${it.key.accountId}/${it.key.fileId}" }) { item ->
                    DownloadRow(item, onCancel = { onCancel(item.key) }, onRetry = { onRetry(item.key) }, onRemove = { onRemove(item.key) })
                    HorizontalDivider()
                }
            }
        }
    }
    confirmAccount?.let { group ->
        ConfirmDialog(
            title = stringResource(R.string.downloads_remove_account_title),
            text = stringResource(R.string.downloads_remove_account_body, group.accountName),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = { onRemoveAccount(group.accountId); confirmAccount = null },
            onDismiss = { confirmAccount = null },
        )
    }
    if (confirmAll) {
        ConfirmDialog(
            title = stringResource(R.string.downloads_remove_all_title),
            text = stringResource(R.string.downloads_remove_all_body),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = { onClearAll(); confirmAll = false },
            onDismiss = { confirmAll = false },
        )
    }
}

@Composable
private fun DownloadRow(item: OfflineItem, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("download_${item.key.fileId}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (item.state) {
                DownloadState.DONE -> Text(formatBytes(context, item.total.takeIf { it > 0 } ?: item.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DownloadState.QUEUED -> Text(stringResource(R.string.download_queued), style = MaterialTheme.typography.bodySmall)
                DownloadState.RUNNING -> {
                    val fraction = if (item.total > 0) (item.bytes.toFloat() / item.total).coerceIn(0f, 1f) else null
                    if (fraction != null) LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "${formatBytes(context, item.bytes)} / ${formatBytes(context, item.total)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DownloadState.FAILED -> Text(
                    item.error?.let { stringResource(R.string.download_failed_reason, it) } ?: stringResource(R.string.download_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        when (item.state) {
            DownloadState.QUEUED, DownloadState.RUNNING -> IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.download_cancel))
            }
            DownloadState.FAILED -> {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry)) }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.action_remove)) }
            }
            DownloadState.DONE -> IconButton(onClick = onRemove, modifier = Modifier.testTag("remove_${item.key.fileId}")) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.action_remove))
            }
        }
    }
}
