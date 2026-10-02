package com.somecatcode.ebookreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.ui.components.LocalCoverLoader
import com.somecatcode.ebookreader.ui.components.createCoverImageLoader
import com.somecatcode.ebookreader.ui.navigation.AppNavHost
import com.somecatcode.ebookreader.ui.theme.EbookReaderTheme
import com.somecatcode.ebookreader.ui.util.LocalAppContainer

/** Single activity of the app: edge-to-edge Compose host with the navigation graph. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        if (savedInstanceState == null) {
            // Sync on app start (unique work, safe to request repeatedly).
            runCatching { container.syncEngine.requestSync() }
        }
        setContent {
            val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
            val dark = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            val coverLoader = remember(container) { createCoverImageLoader(this, container) }
            CompositionLocalProvider(LocalAppContainer provides container, LocalCoverLoader provides coverLoader) {
                EbookReaderTheme(darkTheme = dark, dynamicColor = settings.dynamicColor, einkMode = settings.einkMode) {
                    AppNavHost()
                }
            }
        }
    }
}
