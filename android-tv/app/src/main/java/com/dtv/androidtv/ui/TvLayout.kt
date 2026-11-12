package com.dtv.androidtv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * 10-foot / overscan-safe spacing. Avoids edge-clipped focus rings on TVs.
 * No mouse/hover assumptions — spacing is for DPAD readability only.
 */
object TvLayout {
    val SafeHorizontal = 56.dp
    val SafeVertical = 40.dp
    val RowSpacing = 26.dp
    val CardSpacing = 18.dp
    val SectionTitleBottom = 12.dp
    val SectionGap = 14.dp
    val ButtonPaddingH = 22.dp
    val ButtonPaddingV = 12.dp
    val HeaderBottom = 18.dp

    val ScreenPadding = PaddingValues(
        start = SafeHorizontal,
        top = SafeVertical,
        end = SafeHorizontal,
        bottom = SafeVertical,
    )

    val RowContentPadding = PaddingValues(horizontal = 4.dp)
}
