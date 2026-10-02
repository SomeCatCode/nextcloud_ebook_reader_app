package com.somecatcode.ebookreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF1F4E79),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E4FF),
    onPrimaryContainer = Color(0xFF001C37),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    tertiary = Color(0xFF6B5778),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA0CAFD),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    tertiary = Color(0xFFD6BEE4),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
)

/** High-contrast black on white palette for e-ink displays (no tonal surfaces, no dynamic color). */
private val EinkScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color.Black,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEDEDED),
    outline = Color.Black,
    outlineVariant = Color(0xFF555555),
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color(0xFFE0E0E0),
    onErrorContainer = Color.Black,
)

/** True in e-ink mode: screens skip animations (transitions, ripples are cheap but pull indicators are not). */
val LocalEinkMode = staticCompositionLocalOf { false }

/**
 * App theme: Material 3 with dynamic color on Android 12+ and a fixed palette below;
 * light/dark from [darkTheme]. [einkMode] switches to a high-contrast palette and tells screens
 * to avoid animations through [LocalEinkMode].
 */
@Composable
fun EbookReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    einkMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        einkMode -> EinkScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    CompositionLocalProvider(LocalEinkMode provides einkMode) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
