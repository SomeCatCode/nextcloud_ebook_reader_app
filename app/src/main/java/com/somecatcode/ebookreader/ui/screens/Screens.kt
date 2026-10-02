package com.somecatcode.ebookreader.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.somecatcode.ebookreader.R
import com.somecatcode.ebookreader.ui.components.PlaceholderScreen

/*
 * Phase-0 placeholder screens. W-UI replaces each composable with the real screen in its own file
 * (AccountsScreen.kt, LibraryScreen.kt, ...) and deletes it from here. The signatures below are the
 * navigation contract used by ui/navigation/AppNavHost.kt; extend them only by adding parameters
 * with defaults or by updating AppNavHost in the same change.
 */

/** Account list, add account (Login Flow v2), remove account. */
@Composable
fun AccountsScreen(onBack: () -> Unit) {
    PlaceholderScreen(stringResource(R.string.nav_accounts), onBack = onBack)
}

/** Library (grid/list, search, filters, shelves, series). Start destination. */
@Composable
fun LibraryScreen(
    onOpenAccounts: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (accountId: String, fileId: Long) -> Unit,
) {
    PlaceholderScreen(stringResource(R.string.nav_library)) {
        Button(onClick = onOpenAccounts) { Text(stringResource(R.string.nav_accounts)) }
        Button(onClick = onOpenDownloads) { Text(stringResource(R.string.nav_downloads)) }
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.nav_settings)) }
    }
}

/** Book details, offline toggle, minimal metadata editing. */
@Composable
fun BookDetailScreen(accountId: String, fileId: Long, onBack: () -> Unit, onRead: () -> Unit) {
    PlaceholderScreen(stringResource(R.string.screen_book_detail), onBack = onBack) {
        Button(onClick = onRead) { Text(stringResource(R.string.screen_reader)) }
    }
}

/** Reader screen hosting the WebView (implemented by W-UI together with W-READER's host composable). */
@Composable
fun ReaderScreen(accountId: String, fileId: Long, onBack: () -> Unit) {
    PlaceholderScreen(stringResource(R.string.screen_reader), onBack = onBack)
}

/** Downloads and storage management. */
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    PlaceholderScreen(stringResource(R.string.nav_downloads), onBack = onBack)
}

/** App settings (sync interval, storage location, reader defaults, theme). */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    PlaceholderScreen(stringResource(R.string.nav_settings), onBack = onBack)
}
