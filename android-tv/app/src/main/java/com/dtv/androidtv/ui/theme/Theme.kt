package com.dtv.androidtv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val Background = Color(0xFF0B1020)
private val SurfaceBg = Color(0xFF141B2D)
private val Accent = Color(0xFFE94560)
private val OnBg = Color(0xFFE8ECF5)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DtvTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Color(0xFF4ECDC4),
            background = Background,
            onBackground = OnBg,
            surface = SurfaceBg,
            onSurface = OnBg,
            border = Color(0xFF2A3550),
        ),
        content = content,
    )
}
