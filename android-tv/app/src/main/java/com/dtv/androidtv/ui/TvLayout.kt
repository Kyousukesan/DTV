package com.dtv.androidtv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * 10-foot / overscan-safe spacing aligned with Cinema Ember MASTER.md.
 * Content stays ≥ 48dp from physical edges.
 */
object TvLayout {
    /** Overscan / page edge — MASTER --space-2xl. */
    val SafeHorizontal = 48.dp
    val SafeVertical = 40.dp
    /** Row-to-row vertical gap — MASTER --space-xl. */
    val RowSpacing = 32.dp
    /** Card horizontal gap. */
    val CardSpacing = 18.dp
    val SectionTitleBottom = 14.dp
    val SectionGap = 16.dp
    val ButtonPaddingH = 22.dp
    val ButtonPaddingV = 12.dp
    /** Tab bar → first content row — MASTER --space-3xl approx. */
    val HeaderBottom = 24.dp

    /** Live room card width (design 280–320dp). */
    val CardWidth = 300.dp
    /** Compact shelf cards (follows). */
    val CardWidthCompact = 240.dp

    val ScreenPadding = PaddingValues(
        start = SafeHorizontal,
        top = SafeVertical,
        end = SafeHorizontal,
        bottom = SafeVertical,
    )

    val RowContentPadding = PaddingValues(horizontal = 4.dp)
}
