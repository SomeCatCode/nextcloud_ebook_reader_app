package com.somecatcode.ebookreader.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.somecatcode.ebookreader.ui.screens.AccountsScreen
import com.somecatcode.ebookreader.ui.screens.BookDetailScreen
import com.somecatcode.ebookreader.ui.screens.CollectionId
import com.somecatcode.ebookreader.ui.screens.CollectionScreen
import com.somecatcode.ebookreader.ui.screens.DownloadsScreen
import com.somecatcode.ebookreader.ui.screens.LibraryCommand
import com.somecatcode.ebookreader.ui.screens.LibraryScreen
import com.somecatcode.ebookreader.ui.screens.ReaderScreen
import com.somecatcode.ebookreader.ui.screens.SettingsScreen
import com.somecatcode.ebookreader.ui.theme.LocalEinkMode

/** Navigation graph of the app. Start destination is the library (it shows the welcome state without accounts). */
@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val eink = LocalEinkMode.current
    val bookArgs = listOf(
        navArgument(Routes.ARG_ACCOUNT_ID) { type = NavType.StringType },
        navArgument(Routes.ARG_FILE_ID) { type = NavType.LongType },
    )
    // E-ink displays: no transitions at all.
    val enter: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition =
        if (eink) ({ EnterTransition.None }) else ({ androidx.compose.animation.fadeIn() })
    val exit: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition =
        if (eink) ({ ExitTransition.None }) else ({ androidx.compose.animation.fadeOut() })
    NavHost(
        navController = nav,
        startDestination = Routes.LIBRARY,
        modifier = modifier,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = enter,
        popExitTransition = exit,
    ) {
        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                onBack = { nav.popBackStack() },
                onAccountAdded = { nav.popBackStack(Routes.LIBRARY, inclusive = false) },
            )
        }
        composable(Routes.LIBRARY) { entry ->
            val command by entry.savedStateHandle.getStateFlow<String?>(LibraryCommand.KEY, null).collectAsState()
            LibraryScreen(
                onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
                onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenBook = { accountId, fileId -> nav.navigate(Routes.bookDetail(accountId, fileId)) },
                onOpenShelf = { accountId, shelfId -> nav.navigate(Routes.shelf(accountId, shelfId)) },
                onOpenSeries = { accountId, name -> nav.navigate(Routes.series(accountId, name)) },
                onReadBook = { accountId, fileId -> nav.navigate(Routes.reader(accountId, fileId)) },
                command = command,
                onCommandHandled = { entry.savedStateHandle[LibraryCommand.KEY] = null },
            )
        }
        composable(Routes.BOOK_DETAIL, arguments = bookArgs) { entry ->
            val accountId = entry.arguments?.getString(Routes.ARG_ACCOUNT_ID).orEmpty()
            val fileId = entry.arguments?.getLong(Routes.ARG_FILE_ID) ?: 0L
            BookDetailScreen(
                accountId = accountId,
                fileId = fileId,
                onBack = { nav.popBackStack() },
                onRead = { nav.navigate(Routes.reader(accountId, fileId)) },
                onFilter = { term -> nav.toLibrary(LibraryCommand.only(term)) },
            )
        }
        composable(
            Routes.SHELF,
            arguments = listOf(
                navArgument(Routes.ARG_ACCOUNT_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_SHELF_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val accountId = entry.arguments?.getString(Routes.ARG_ACCOUNT_ID).orEmpty()
            val shelfId = entry.arguments?.getLong(Routes.ARG_SHELF_ID) ?: 0L
            CollectionScreen(
                id = CollectionId.Shelf(accountId, shelfId),
                onBack = { nav.popBackStack() },
                onOpenBook = { a, f -> nav.navigate(Routes.bookDetail(a, f)) },
                onEditSmartShelf = { a, id -> nav.toLibrary(LibraryCommand.editSmart(a, id)) },
            )
        }
        composable(
            Routes.SERIES,
            arguments = listOf(
                navArgument(Routes.ARG_ACCOUNT_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_SERIES_NAME) { type = NavType.StringType },
            ),
        ) { entry ->
            val accountId = entry.arguments?.getString(Routes.ARG_ACCOUNT_ID).orEmpty()
            val name = entry.arguments?.getString(Routes.ARG_SERIES_NAME).orEmpty()
            CollectionScreen(
                id = CollectionId.Series(accountId, name),
                onBack = { nav.popBackStack() },
                onOpenBook = { a, f -> nav.navigate(Routes.bookDetail(a, f)) },
            )
        }
        composable(Routes.READER, arguments = bookArgs) { entry ->
            ReaderScreen(
                accountId = entry.arguments?.getString(Routes.ARG_ACCOUNT_ID).orEmpty(),
                fileId = entry.arguments?.getLong(Routes.ARG_FILE_ID) ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.DOWNLOADS,
            deepLinks = listOf(navDeepLink { uriPattern = Routes.DOWNLOADS_DEEP_LINK }),
        ) {
            DownloadsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenLicenses = { nav.navigate(Routes.LICENSES) })
        }
        composable(Routes.LICENSES) {
            com.somecatcode.ebookreader.ui.screens.LicensesScreen(onBack = { nav.popBackStack() })
        }
    }
}

/** Back to the library (always at the bottom of the stack) and hand it a [LibraryCommand]. */
private fun NavHostController.toLibrary(command: String) {
    runCatching { getBackStackEntry(Routes.LIBRARY) }.getOrNull()?.savedStateHandle?.set(LibraryCommand.KEY, command)
    popBackStack(Routes.LIBRARY, inclusive = false)
}
