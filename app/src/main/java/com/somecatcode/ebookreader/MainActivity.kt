package com.somecatcode.ebookreader

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.somecatcode.ebookreader.data.repo.AppSettings
import com.somecatcode.ebookreader.ui.components.LocalCoverLoader
import com.somecatcode.ebookreader.ui.components.createCoverImageLoader
import com.somecatcode.ebookreader.ui.navigation.AppNavHost
import com.somecatcode.ebookreader.ui.theme.EbookReaderTheme
import com.somecatcode.ebookreader.ui.util.LocalAppContainer

/**
 * Single activity of the app: edge-to-edge Compose host with the navigation graph.
 *
 * Edge-to-edge does not use `androidx.activity.enableEdgeToEdge()`: it calls `Window.setStatusBarColor`,
 * `Window.setNavigationBarColor` and `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`, which Android 15 deprecates
 * (Google Play reports them). The theme makes the system bars transparent (the themes.xml files in res), the window
 * draws behind them, and [applySystemBarIcons] picks dark or light bar icons for the app theme.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
            // The app theme can differ from the system theme: the bar icons follow the app theme.
            SideEffect { applySystemBarIcons(dark) }
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

    private fun applySystemBarIcons(dark: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        // Before Android 10 the navigation bar keeps a dark scrim (themes.xml), so its icons stay light.
        controller.isAppearanceLightNavigationBars = !dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
