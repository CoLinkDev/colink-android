package com.colink.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

private val LightScheme: ColorScheme =
    lightColorScheme(
        primary = CoLinkBlue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD8E2FF),
        onPrimaryContainer = Color(0xFF001A43),
        secondary = Color(0xFF146C43),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFB7F1CF),
        onSecondaryContainer = Color(0xFF002110),
        tertiary = Color(0xFF7B4E00),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDDB3),
        onTertiaryContainer = Color(0xFF281800),
        background = CoLinkSurface,
        onBackground = CoLinkInk,
        surface = Color(0xFFFCFCFF),
        onSurface = CoLinkInk,
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44474F),
        outline = Color(0xFF74777F),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
    )

private val DarkScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFAEC6FF),
        onPrimary = Color(0xFF002F6E),
        primaryContainer = Color(0xFF00439F),
        onPrimaryContainer = Color(0xFFD8E2FF),
        secondary = Color(0xFF9AD4B4),
        onSecondary = Color(0xFF00391F),
        secondaryContainer = Color(0xFF005230),
        onSecondaryContainer = Color(0xFFB7F1CF),
        tertiary = Color(0xFFFFB955),
        onTertiary = Color(0xFF422C00),
        tertiaryContainer = Color(0xFF5E4200),
        onTertiaryContainer = Color(0xFFFFDDB3),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE3E2E8),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE3E2E8),
        surfaceVariant = Color(0xFF44474F),
        onSurfaceVariant = Color(0xFFC4C6D0),
        outline = Color(0xFF8E9099),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

@Composable
fun CoLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme, dynamicColor) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkScheme
            else -> LightScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
