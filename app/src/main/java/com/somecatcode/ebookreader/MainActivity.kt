package com.somecatcode.ebookreader

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.ui.components.LocalCoverLoader
import com.somecatcode.ebookreader.ui.components.createCoverImageLoader
import com.somecatcode.ebookreader.ui.navigation.AppNavHost
import com.somecatcode.ebookreader.ui.theme.EbookReaderTheme
import com.somecatcode.ebookreader.ui.util.LocalAppContainer

/** Single activity of the app: edge-to-edge Compose host with the navigation graph. */
class MainActivity : AppCompatActivity() {
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
                    // Scaffolds and top bars only pad for system bars; keep content clear of a side camera cutout in landscape.
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
                    ) {
                        AppNavHost()
                    }
                }
            }
        }
    }
}
