package com.somecatcode.ebookreader.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.account.Account
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.components.ConfirmDialog
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.util.containerViewModel
import com.somecatcode.ebookreader.ui.util.formatRelativeTime
import com.somecatcode.ebookreader.ui.util.serverLabel
import com.somecatcode.ebookreader.ui.util.title

/** Account list, add account (Login Flow v2), remove account. */
@Composable
fun AccountsScreen(onBack: () -> Unit, onAccountAdded: () -> Unit = {}) {
    val vm = containerViewModel { c ->
        AccountsViewModel(c.accountStore, c.loginFlowClient, c.apiClientFactory, c.syncEngine)
    }
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is AccountsEvent.OpenBrowser -> openBrowser(context, event.url)
                is AccountsEvent.AccountAdded -> onAccountAdded()
            }
        }
    }
    AccountsContent(
        state = state,
        onBack = onBack,
        onAdd = vm::showAddAccount,
        onRelogin = vm::showRelogin,
        onSync = vm::sync,
        onRemove = vm::askRemove,
        addActions = AddFlowActions(
            onInputChange = vm::onServerInputChange,
            onSignIn = vm::startLogin,
            onCancelWaiting = vm::cancelLogin,
            onReopenBrowser = vm::reopenBrowser,
            onDismiss = vm::dismissAddFlow,
        ),
        onConfirmRemove = vm::confirmRemove,
        onDismissRemove = vm::dismissRemove,
        onDismissRevokeHint = vm::dismissRevokeHint,
    )
}

private fun openBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: the waiting dialog still offers "Open browser again".
    }
}

class AddFlowActions(
    val onInputChange: (String) -> Unit,
    val onSignIn: () -> Unit,
    val onCancelWaiting: () -> Unit,
    val onReopenBrowser: () -> Unit,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsContent(
    state: AccountsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRelogin: (Account) -> Unit,
    onSync: (Account) -> Unit,
    onRemove: (Account) -> Unit,
    addActions: AddFlowActions,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    onDismissRevokeHint: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.nav_accounts)) }, navigationIcon = { BackButton(onBack) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.accounts_add)) },
                modifier = Modifier.testTag("add_account"),
            )
        },
    ) { padding ->
        if (state.loaded && state.rows.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.accounts_empty_title),
                body = stringResource(R.string.accounts_empty_body),
                actionLabel = stringResource(R.string.accounts_add),
                onAction = onAdd,
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.rows, key = { it.account.id }) { row ->
                    AccountCard(row, onRelogin = { onRelogin(row.account) }, onSync = { onSync(row.account) }, onRemove = { onRemove(row.account) })
                }
            }
        }
    }

    state.addFlow?.let { AddAccountDialog(it, addActions) }
    state.removeCandidate?.let { account ->
        ConfirmDialog(
            title = stringResource(R.string.remove_title),
            text = stringResource(R.string.remove_body, account.title(), account.serverLabel()),
            confirmLabel = stringResource(R.string.remove_confirm),
            onConfirm = onConfirmRemove,
            onDismiss = onDismissRemove,
        )
    }
    if (state.revokeFailedHint) {
        AlertDialog(
            onDismissRequest = onDismissRevokeHint,
            title = { Text(stringResource(R.string.remove_revoke_failed_title)) },
            text = { Text(stringResource(R.string.remove_revoke_failed_body)) },
            confirmButton = { TextButton(onClick = onDismissRevokeHint) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun AccountCard(row: AccountRow, onRelogin: () -> Unit, onSync: () -> Unit, onRemove: () -> Unit) {
    val account = row.account
    Card(Modifier.fillMaxWidth().testTag("account_${account.id}")) {
        Column(Modifier.padding(16.dp)) {
            Text(account.title(), style = MaterialTheme.typography.titleMedium)
            Text(
                "${account.loginName} @ ${account.serverLabel()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sync = account.lastSyncAt?.let { stringResource(R.string.account_last_sync, formatRelativeTime(it)) }
                ?: stringResource(R.string.account_never_synced)
            Text(sync, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val problem = row.status in setOf(AccountStatus.AUTH_EXPIRED, AccountStatus.APP_UNAVAILABLE, AccountStatus.ERROR)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.status == AccountStatus.SYNCING) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).testTag("syncing"), strokeWidth = 2.dp)
                }
                Text(
                    stringResource(statusText(row.status)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (row.status == AccountStatus.AUTH_EXPIRED) {
                    TextButton(onClick = onRelogin) { Text(stringResource(R.string.account_sign_in_again)) }
                } else {
                    TextButton(onClick = onSync) { Text(stringResource(R.string.account_sync_now)) }
                }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.account_remove)) }
            }
        }
    }
}

private fun statusText(status: AccountStatus): Int = when (status) {
    AccountStatus.OK -> R.string.status_ok
    AccountStatus.SYNCING -> R.string.status_syncing
    AccountStatus.OFFLINE -> R.string.status_offline
    AccountStatus.AUTH_EXPIRED -> R.string.status_auth_expired
    AccountStatus.APP_UNAVAILABLE -> R.string.status_app_unavailable
    AccountStatus.ERROR -> R.string.status_error
}

private fun errorText(error: AddError): Int = when (error) {
    AddError.INVALID_URL -> R.string.add_error_invalid_url
    AddError.NETWORK -> R.string.add_error_network
    AddError.TIMEOUT -> R.string.add_error_timeout
    AddError.APP_MISSING -> R.string.add_error_app_missing
    AddError.APP_TOO_OLD -> R.string.add_error_app_too_old
    AddError.DUPLICATE -> R.string.add_error_duplicate
    AddError.WRONG_USER -> R.string.add_error_wrong_user
    AddError.REJECTED -> R.string.add_error_rejected
    AddError.GENERIC -> R.string.add_error_generic
}

@Composable
private fun AddAccountDialog(flow: AddFlowState, actions: AddFlowActions) {
    val relogin = flow.reloginAccountId != null
    val title = stringResource(if (relogin) R.string.relogin_title else R.string.add_title)
    when (flow.stage) {
        AddStage.INPUT -> AlertDialog(
            onDismissRequest = actions.onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    Text(stringResource(R.string.add_server_intro), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = flow.serverInput,
                        onValueChange = actions.onInputChange,
                        label = { Text(stringResource(R.string.add_server_label)) },
                        placeholder = { Text(stringResource(R.string.add_server_hint)) },
                        singleLine = true,
                        isError = flow.error != null,
                        readOnly = relogin,
                        supportingText = flow.error?.let { { Text(stringResource(errorText(it))) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { actions.onSignIn() }),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("server_input"),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onSignIn, enabled = flow.serverInput.isNotBlank(), modifier = Modifier.testTag("sign_in")) {
                    Text(stringResource(R.string.add_sign_in))
                }
            },
            dismissButton = { TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        AddStage.STARTING, AddStage.VERIFYING -> AlertDialog(
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text(stringResource(if (flow.stage == AddStage.STARTING) R.string.add_starting else R.string.add_verifying))
                }
            },
            confirmButton = {},
        )
        AddStage.WAITING -> AlertDialog(
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp))
                        Text(stringResource(R.string.add_waiting), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(stringResource(R.string.add_waiting_body), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = actions.onReopenBrowser) { Text(stringResource(R.string.add_open_browser)) } },
            dismissButton = {
                TextButton(onClick = actions.onCancelWaiting, modifier = Modifier.testTag("cancel_login")) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
