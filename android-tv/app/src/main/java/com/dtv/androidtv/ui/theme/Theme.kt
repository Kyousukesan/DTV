package com.dtv.androidtv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Cinema Ember (影院暖金) design tokens — SoT:
 * docs/design/android-tv-live-aggregator/MASTER.md § Color / Focus.
 * No purple/indigo brand accents.
 */
object CinemaEmber {
    val BgApp = Color(0xFF0B0D10)
    val BgElevated = Color(0xFF12161C)
    val BgSurface = Color(0xFF161A20)
    val BgSurfaceHover = Color(0xFF1E2430)
    val BgSkeleton = Color(0xFF1A1F28)
    val Overlay = Color(0x8C000000) // 55% black

    val TextPrimary = Color(0xFFF3F5F7)
    val TextSecondary = Color(0xFF9AA3AD)
    val TextDisabled = Color(0xFF5C6570)
    val TextOnAccent = Color(0xFF14110A)

    val Accent = Color(0xFFE8B84A)
    val AccentMuted = Color(0x3DE8B84A) // 24% accent
    val FocusRing = Color(0xFFF5C84C)
    val FocusGlow = Color(0x47F5C84C) // 28% focus ring

    val BorderSubtle = Color(0xFF2A313C)
    val BorderStrong = Color(0xFF3D4654)

    val Live = Color(0xFF2BB673)
    val LiveText = Color(0xFFE8FFF3)
    val Offline = Color(0xFF5C6570)
    val Danger = Color(0xFFE5484D)
    val DangerBg = Color(0x29E5484D) // 16%

    /** Focus scale per MASTER --focus-scale. */
    const val FocusScale = 1.06f
    const val PressScale = 1.03f

    val RadiusCard = 12.dp
    val RadiusControl = 8.dp
    val FocusStroke = 3.dp
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DtvTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CinemaEmber.Accent,
            onPrimary = CinemaEmber.TextOnAccent,
            secondary = CinemaEmber.FocusRing,
            onSecondary = CinemaEmber.TextOnAccent,
            background = CinemaEmber.BgApp,
            onBackground = CinemaEmber.TextPrimary,
            surface = CinemaEmber.BgSurface,
            onSurface = CinemaEmber.TextPrimary,
            border = CinemaEmber.BorderSubtle,
            error = CinemaEmber.Danger,
        ),
        content = content,
    )
}
