package com.dtv.androidtv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * 10-foot / overscan-safe spacing. Avoids edge-clipped focus rings on TVs.
 * No mouse/hover assumptions — spacing is for DPAD readability only.
 */
object TvLayout {
    val SafeHorizontal = 48.dp
    val SafeVertical = 36.dp
    val RowSpacing = 22.dp
    val CardSpacing = 16.dp
    val SectionTitleBottom = 10.dp

    val ScreenPadding = PaddingValues(
        start = SafeHorizontal,
        top = SafeVertical,
        end = SafeHorizontal,
        bottom = SafeVertical,
    )

    val RowContentPadding = PaddingValues(horizontal = 4.dp)
}
