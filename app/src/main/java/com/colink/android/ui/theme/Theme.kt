package com.colink.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme: ColorScheme =
    lightColorScheme(
        primary = Blue,
        secondary = Green,
        background = Surface,
        surface = Color.White,
        onPrimary = Color.White,
        onBackground = Ink,
        onSurface = Ink,
    )

@Composable
fun CoLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
