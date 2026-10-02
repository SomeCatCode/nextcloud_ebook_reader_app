package com.somecatcode.ebookreader.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.somecatcode.ebookreader.ui.navigation.AppNavHost
import com.somecatcode.ebookreader.ui.theme.EbookReaderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric Compose test: the placeholder navigation graph starts in the library and reaches Settings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NavigationSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun libraryIsStartDestinationAndSettingsIsReachable() {
        compose.setContent { EbookReaderTheme { AppNavHost() } }
        compose.onAllNodesWithText("Library").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onAllNodesWithText("Settings").onFirst().assertIsDisplayed()
    }
}
