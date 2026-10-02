package com.somecatcode.ebookreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.db.DownloadState
import com.somecatcode.ebookreader.data.repo.OfflineState

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

/** Centered empty/error state with an optional action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) { Text(actionLabel) }
        }
    }
}

/** Inline banner (offline, sign-in expired, ...). */
@Composable
fun MessageBanner(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier.fillMaxWidth(),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Five tappable stars. [rating] null/0 = unrated; tapping the current rating clears it. */
@Composable
fun StarRating(rating: Int?, onRatingChange: ((Int?) -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier) {
        for (i in 1..5) {
            val filled = (rating ?: 0) >= i
            val icon = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder
            if (onRatingChange != null) {
                val description = stringResource(R.string.rating_stars, i)
                IconButton(onClick = { onRatingChange(if (rating == i) null else i) }) {
                    Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Small offline indicator: check = available, spinner = downloading, error icon = failed. */
@Composable
fun OfflineIndicator(state: OfflineState, modifier: Modifier = Modifier) {
    when (state.state) {
        null -> Unit
        DownloadState.DONE -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.offline_available),
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(20.dp),
        )
        DownloadState.FAILED -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = stringResource(R.string.download_failed),
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(20.dp),
        )
        DownloadState.QUEUED, DownloadState.RUNNING -> {
            val description = stringResource(R.string.download_in_progress)
            val fraction = if (state.total > 0) (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) else null
            val sized = modifier.size(20.dp).semantics { contentDescription = description }
            if (fraction != null && state.state == DownloadState.RUNNING) {
                CircularProgressIndicator({ fraction }, sized, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(sized, strokeWidth = 2.dp)
            }
        }
    }
}

/** Dialog with one checkbox per option; returns the new selection on confirm. */
@Composable
fun MultiSelectDialog(
    title: String,
    options: List<Pair<String, Int?>>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var current by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (options.isEmpty()) {
                Text(stringResource(R.string.filter_none_available))
            } else {
                LazyColumn(Modifier.selectableGroup()) {
                    items(options.size) { index ->
                        val (name, count) = options[index]
                        val checked = name in current
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(checked, role = Role.Checkbox) {
                                    current = if (checked) current - name else current + name
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(name, Modifier.padding(start = 12.dp).weight(1f))
                            if (count != null) Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(current) }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = {
            TextButton(onClick = { current = emptySet() }) { Text(stringResource(R.string.action_clear)) }
        },
    )
}

/** Confirmation dialog helper. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ClickableRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) { content() }
}
