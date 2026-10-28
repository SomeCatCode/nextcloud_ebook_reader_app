package com.somecatcode.ebookreader.ui.navigation

import com.somecatcode.ebookreader.ui.screens.GuideScreen
import com.somecatcode.ebookreader.ui.util.LocalAppContainer
import com.somecatcode.ebookreader.ui.util.UiPrefs
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import com.somecatcode.ebookreader.ui.screens.HomeScreen
import com.somecatcode.ebookreader.ui.screens.LibraryCommand
import com.somecatcode.ebookreader.ui.screens.LibraryScreen
import com.somecatcode.ebookreader.ui.screens.ReaderScreen
import com.somecatcode.ebookreader.ui.screens.SettingsScreen
import com.somecatcode.ebookreader.ui.theme.LocalEinkMode

/**
 * Navigation graph of the app. Start destination is the home screen (it shows the welcome state without
 * accounts); the library is one step above it. At the very first start the introduction comes first
 * ([showGuideOnFirstStart]).
 */
@Composable
fun AppNavHost(modifier: Modifier = Modifier, showGuideOnFirstStart: Boolean = true) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val prefs = remember { UiPrefs(context) }
    val start = remember { if (showGuideOnFirstStart && !prefs.guideShown) Routes.GUIDE else Routes.HOME }
    val accounts by LocalAppContainer.current.accountStore.accounts.collectAsState(initial = null)
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
        startDestination = start,
        modifier = modifier,
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = enter,
        popExitTransition = exit,
    ) {
        composable(Routes.GUIDE) {
            GuideScreen(hasLibrary = !accounts.isNullOrEmpty()) { addLibrary ->
                prefs.guideShown = true
                // first start: the home screen replaces the guide at the bottom of the stack
                if (!nav.popBackStack()) nav.navigate(Routes.HOME) { popUpTo(Routes.GUIDE) { inclusive = true } }
                if (addLibrary) nav.navigate(Routes.ACCOUNTS)
            }
        }
        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                onBack = { nav.popBackStack() },
                onAccountAdded = { if (!nav.popBackStack(Routes.LIBRARY, inclusive = false)) nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenLibrary = { command -> nav.toLibrary(command) },
                onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
                onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenBook = { accountId, fileId -> nav.navigate(Routes.bookDetail(accountId, fileId)) },
                onOpenShelf = { accountId, shelfId -> nav.navigate(Routes.shelf(accountId, shelfId)) },
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
                onReadBook = { other -> nav.navigate(Routes.reader(other.accountId, other.fileId)) },
                onOpenBook = { other -> nav.navigate(Routes.bookDetail(other.accountId, other.fileId)) },
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
                // the next volume replaces this reader: back leads to the screen that opened the finished book
                onReadNext = { next -> nav.navigate(Routes.reader(next.accountId, next.fileId)) { popUpTo(Routes.READER) { inclusive = true } } },
                onOpenBook = { other -> nav.navigate(Routes.bookDetail(other.accountId, other.fileId)) },
            )
        }
        composable(
            Routes.DOWNLOADS,
            deepLinks = listOf(navDeepLink { uriPattern = Routes.DOWNLOADS_DEEP_LINK }),
        ) {
            DownloadsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenLicenses = { nav.navigate(Routes.LICENSES) },
                onOpenPrivacy = { nav.navigate(Routes.PRIVACY) },
                onOpenGuide = { nav.navigate(Routes.GUIDE) },
            )
        }
        composable(Routes.LICENSES) {
            com.somecatcode.ebookreader.ui.screens.LicensesScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.PRIVACY) {
            com.somecatcode.ebookreader.ui.screens.PrivacyScreen(onBack = { nav.popBackStack() })
        }
    }
}

/**
 * To the library with an optional [LibraryCommand]. The library sits directly above the home screen: when it is
 * already in the back stack, pop back to it; otherwise (opened from the home screen) open it.
 */
private fun NavHostController.toLibrary(command: String?) {
    val existing = runCatching { getBackStackEntry(Routes.LIBRARY) }.getOrNull()
    if (existing != null) {
        if (command != null) existing.savedStateHandle[LibraryCommand.KEY] = command
        popBackStack(Routes.LIBRARY, inclusive = false)
        return
    }
    navigate(Routes.LIBRARY) { launchSingleTop = true }
    if (command != null) runCatching { getBackStackEntry(Routes.LIBRARY) }.getOrNull()?.savedStateHandle?.set(LibraryCommand.KEY, command)
}
