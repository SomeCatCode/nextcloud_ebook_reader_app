package com.somecatcode.ebookreader.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.somecatcode.ebookreader.ui.navigation.AppNavHost
import com.somecatcode.ebookreader.ui.theme.EbookReaderTheme
import com.somecatcode.ebookreader.ui.util.LocalAppContainer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric Compose test: the graph starts in the library and reaches Settings and Downloads. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NavigationSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun libraryIsStartDestinationAndOverflowReachesSettingsAndDownloads() {
        val container = FakeContainer(ApplicationProvider.getApplicationContext())
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { EbookReaderTheme(dynamicColor = false) { AppNavHost() } }
        }
        // first start: the introduction comes first, skipping it leads to the library
        compose.onNodeWithText("Welcome").assertIsDisplayed()
        compose.onNodeWithTag("guide_skip").performClick()
        compose.onNodeWithText("Books").assertIsDisplayed()
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun guideWalksThroughAllPagesAndOffersToAddALibraryWithoutAccounts() {
        val container = FakeContainer(ApplicationProvider.getApplicationContext(), accountStore = FakeAccountStore())
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { EbookReaderTheme(dynamicColor = false) { AppNavHost() } }
        }
        repeat(4) {
            compose.onNodeWithTag("guide_next").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Offline, copy and move").assertIsDisplayed()
        compose.onNodeWithText("Add library").performClick()
        compose.onNodeWithText("No accounts yet").assertIsDisplayed()
        compose.onNodeWithTag("guide").assertDoesNotExist()
    }
}
