package com.somecatcode.ebookreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.somecatcode.ebookreader.ui.screens.AccountsScreen
import com.somecatcode.ebookreader.ui.screens.BookDetailScreen
import com.somecatcode.ebookreader.ui.screens.DownloadsScreen
import com.somecatcode.ebookreader.ui.screens.LibraryScreen
import com.somecatcode.ebookreader.ui.screens.ReaderScreen
import com.somecatcode.ebookreader.ui.screens.SettingsScreen

/** Navigation graph of the app. Start destination is the library; W-UI redirects to Accounts when none exists. */
@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val bookArgs = listOf(
        navArgument(Routes.ARG_ACCOUNT_ID) { type = NavType.StringType },
        navArgument(Routes.ARG_FILE_ID) { type = NavType.LongType },
    )
    NavHost(navController = nav, startDestination = Routes.LIBRARY, modifier = modifier) {
        composable(Routes.ACCOUNTS) {
            AccountsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
                onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenBook = { accountId, fileId -> nav.navigate(Routes.bookDetail(accountId, fileId)) },
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
            )
        }
        composable(Routes.READER, arguments = bookArgs) { entry ->
            ReaderScreen(
                accountId = entry.arguments?.getString(Routes.ARG_ACCOUNT_ID).orEmpty(),
                fileId = entry.arguments?.getLong(Routes.ARG_FILE_ID) ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
