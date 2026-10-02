package com.somecatcode.ebookreader.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.data.repo.BookKey
import com.somecatcode.ebookreader.data.repo.ProgressConflict
import com.somecatcode.ebookreader.reader.HostToReader
import com.somecatcode.ebookreader.reader.ReaderHost
import com.somecatcode.ebookreader.reader.ReaderSettings
import com.somecatcode.ebookreader.reader.TocItem
import com.somecatcode.ebookreader.ui.components.BackButton
import com.somecatcode.ebookreader.ui.components.EmptyState
import com.somecatcode.ebookreader.ui.util.LocalAppContainer
import com.somecatcode.ebookreader.ui.util.UiPrefs
import com.somecatcode.ebookreader.ui.util.containerViewModel
import com.somecatcode.ebookreader.ui.util.percentText

/** Full-screen reader: hosts W-READER's WebView through [ReaderHost], handles conflict, TOC, settings, links. */
@Composable
fun ReaderScreen(accountId: String, fileId: Long, onBack: () -> Unit) {
    val key = BookKey(accountId, fileId)
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val vm = containerViewModel(key = "reader/$accountId/$fileId") { c ->
        val prefs = UiPrefs(c.appContext)
        ReaderViewModel(
            key, c.libraryRepository, c.progressRepository, c.downloadRepository, c.settingsRepository,
            keepScreenOn = prefs.readerKeepScreenOn,
            onKeepScreenOnChanged = { prefs.readerKeepScreenOn = it },
        )
    }
    val host = remember(key) { createReaderHost(context, container, key) }
    DisposableEffect(host) {
        onDispose {
            vm.flushProgress()
            host.send(HostToReader.Destroy)
            host.destroy()
        }
    }
    LaunchedEffect(host) { host.events.collect(vm::onEvent) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.flushProgress() }

    val state by vm.state.collectAsState()
    LaunchedEffect(state.openMessage) { state.openMessage?.let(host::send) }
    LaunchedEffect(state.settings, state.phase) {
        if (state.phase == ReaderPhase.READING) host.send(HostToReader.SetSettings(state.settings))
    }

    ReaderSystemBars(hidden = true)
    KeepScreenOn(state.keepScreenOn)
    BackHandler(enabled = state.showToc || state.showSettings || state.barsVisible) {
        when {
            state.showToc -> vm.showToc(false)
            state.showSettings -> vm.showSettings(false)
            else -> vm.toggleBars()
        }
    }

    ReaderContent(
        state = state,
        surface = { modifier -> ReaderSurface(host, modifier) },
        onBack = onBack,
        onJumpRemote = { vm.resolveConflict(true) },
        onKeepLocal = { vm.resolveConflict(false) },
        onShowToc = { vm.showToc(true) },
        onHideToc = { vm.showToc(false) },
        onTocClick = { item ->
            host.send(HostToReader.GoTo(href = item.href))
            vm.showToc(false)
        },
        onShowSettings = { vm.showSettings(true) },
        onHideSettings = { vm.showSettings(false) },
        onSettings = vm::updateSettings,
        onKeepScreenOn = vm::setKeepScreenOn,
        onConfirmLink = { url ->
            vm.dismissExternalLink()
            openExternal(context, url)
        },
        onDismissLink = vm::dismissExternalLink,
    )
}

private fun openExternal(context: Context, url: String) {
    if (!isSafeExternalUrl(url)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing can open the link.
    }
}

@Composable
private fun ReaderSystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null && hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun KeepScreenOn(keep: Boolean) {
    val view = LocalView.current
    DisposableEffect(keep) {
        view.keepScreenOn = keep
        onDispose { view.keepScreenOn = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderContent(
    state: ReaderUiState,
    surface: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    onJumpRemote: () -> Unit,
    onKeepLocal: () -> Unit,
    onShowToc: () -> Unit,
    onHideToc: () -> Unit,
    onTocClick: (TocItem) -> Unit,
    onShowSettings: () -> Unit,
    onHideSettings: () -> Unit,
    onSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onConfirmLink: (String) -> Unit,
    onDismissLink: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            if (state.phase != ReaderPhase.CONFLICT && state.phase != ReaderPhase.ERROR) surface(Modifier.fillMaxSize())

            when (state.phase) {
                ReaderPhase.LOADING, ReaderPhase.OPENING -> CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("reader_loading"))
                ReaderPhase.ERROR -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.statusBarsPadding()) { BackButton(onBack) }
                    EmptyState(
                        title = stringResource(R.string.reader_error_title),
                        body = stringResource(failureText(state.failure)),
                        actionLabel = stringResource(R.string.action_back),
                        onAction = onBack,
                    )
                }
                else -> Unit
            }

            if (state.barsVisible && state.phase != ReaderPhase.ERROR) {
                Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 2.dp) {
                    Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton(onBack)
                        Text(state.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onShowToc, enabled = state.toc.isNotEmpty(), modifier = Modifier.testTag("reader_toc")) {
                            Icon(Icons.AutoMirrored.Filled.Toc, contentDescription = stringResource(R.string.reader_toc))
                        }
                        IconButton(onClick = onShowSettings, modifier = Modifier.testTag("reader_settings")) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.reader_settings))
                        }
                    }
                }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 2.dp) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            listOfNotNull(state.label, percentText(state.percentage)).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        LinearProgressIndicator({ state.percentage.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
        }
    }

    state.conflict?.let { ConflictDialog(it, onJumpRemote, onKeepLocal) }
    if (state.showToc) {
        ModalBottomSheet(onDismissRequest = onHideToc) { TocList(state.toc, onTocClick) }
    }
    if (state.showSettings) {
        ModalBottomSheet(onDismissRequest = onHideSettings) {
            ReaderSettingsSheet(state.settings, state.keepScreenOn, onSettings, onKeepScreenOn)
        }
    }
    state.externalLink?.let { url ->
        AlertDialog(
            onDismissRequest = onDismissLink,
            title = { Text(stringResource(R.string.reader_link_title)) },
            text = { Text(stringResource(R.string.reader_link_body, url)) },
            confirmButton = { TextButton(onClick = { onConfirmLink(url) }, modifier = Modifier.testTag("link_open")) { Text(stringResource(R.string.reader_link_open)) } },
            dismissButton = { TextButton(onClick = onDismissLink) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun failureText(failure: ReaderFailure?): Int = when (failure) {
    ReaderFailure.NOT_FOUND -> R.string.book_not_found
    ReaderFailure.NOT_DOWNLOADABLE -> R.string.book_view_only
    ReaderFailure.NETWORK -> R.string.reader_error_network
    ReaderFailure.UNAUTHORIZED -> R.string.banner_auth_expired
    ReaderFailure.UNSUPPORTED -> R.string.reader_error_unsupported
    ReaderFailure.OPEN_FAILED, null -> R.string.reader_error_open
}

@Composable
private fun ConflictDialog(conflict: ProgressConflict, onJump: () -> Unit, onKeep: () -> Unit) {
    val device = conflict.remoteDevice?.takeIf { it.isNotBlank() } ?: stringResource(R.string.reader_conflict_unknown_device)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.reader_conflict_title, device)) },
        text = {
            Text(stringResource(R.string.reader_conflict_body, percentText(conflict.remotePercentage), percentText(conflict.localPercentage)))
        },
        confirmButton = { TextButton(onClick = onJump, modifier = Modifier.testTag("conflict_jump")) { Text(stringResource(R.string.reader_conflict_jump)) } },
        dismissButton = { TextButton(onClick = onKeep, modifier = Modifier.testTag("conflict_keep")) { Text(stringResource(R.string.reader_conflict_keep)) } },
    )
}

private fun flatten(items: List<TocItem>, depth: Int = 0): List<Pair<Int, TocItem>> =
    items.flatMap { listOf(depth to it) + flatten(it.subitems, depth + 1) }

@Composable
private fun TocList(items: List<TocItem>, onClick: (TocItem) -> Unit) {
    val flat = remember(items) { flatten(items) }
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
        items(flat.size) { index ->
            val (depth, item) = flat[index]
            Text(
                item.label.ifBlank { item.href },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onClick(item) }.padding(start = (16 + depth * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderSettingsSheet(
    settings: ReaderSettings,
    keepScreenOn: Boolean,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_reader_font_size, settings.fontSize), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = settings.fontSize.toFloat(),
            onValueChange = { v -> onChange { it.copy(fontSize = (v / 5f).toInt() * 5) } },
            valueRange = 60f..200f,
        )
        Text(stringResource(R.string.settings_reader_theme), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to R.string.reader_theme_auto, "light" to R.string.theme_light, "sepia" to R.string.reader_theme_sepia, "dark" to R.string.theme_dark)
                .forEach { (theme, label) ->
                    FilterChip(settings.theme == theme, { onChange { it.copy(theme = theme) } }, { Text(stringResource(label)) })
                }
        }
        Text(stringResource(R.string.reader_flow), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("paginated" to R.string.reader_flow_paginated, "scrolled" to R.string.reader_flow_scrolled).forEach { (flow, label) ->
                FilterChip(settings.flow == flow, { onChange { it.copy(flow = flow) } }, { Text(stringResource(label)) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_keep_screen_on), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(keepScreenOn, onKeepScreenOn)
        }
    }
}
