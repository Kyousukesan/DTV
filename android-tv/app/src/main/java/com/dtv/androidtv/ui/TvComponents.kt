package com.dtv.androidtv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.dtv.androidtv.ui.theme.CinemaEmber

/** Flat Cinema Ember backdrop (no purple/indigo gradient). */
@Composable
fun TvScreenBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaEmber.BgApp),
    ) {
        content()
    }
}

/**
 * Primary DPAD action chip used in headers and chrome.
 * Focus: warm-gold container + scale (MASTER focus ring semantics via TV Surface).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val idleColor = if (emphasized) {
        CinemaEmber.Accent
    } else {
        CinemaEmber.BgElevated
    }
    val idleText = if (emphasized) CinemaEmber.TextOnAccent else CinemaEmber.TextPrimary
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(CinemaEmber.RadiusControl),
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = CinemaEmber.FocusScale,
            pressedScale = CinemaEmber.PressScale,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(CinemaEmber.FocusStroke, CinemaEmber.FocusRing),
                shape = RoundedCornerShape(CinemaEmber.RadiusControl),
            ),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = idleColor,
            focusedContainerColor = CinemaEmber.AccentMuted,
            pressedContainerColor = CinemaEmber.Accent.copy(alpha = 0.55f),
            contentColor = idleText,
            focusedContentColor = CinemaEmber.TextPrimary,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = TvLayout.ButtonPaddingH,
                vertical = TvLayout.ButtonPaddingV,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = idleText,
        )
    }
}

/** Centered empty / error panel with optional primary action. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvStatusBlock(
    title: String,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = CinemaEmber.TextPrimary,
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaEmber.TextSecondary,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(24.dp))
                TvActionButton(label = actionLabel, onClick = onAction, emphasized = true)
            }
        }
    }
}

/** Settings / search section title + optional subtitle. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = CinemaEmber.TextPrimary,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = CinemaEmber.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(TvLayout.SectionGap))
    }
}

/** Full-width settings row with trailing action affordance. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(CinemaEmber.RadiusControl),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(CinemaEmber.FocusStroke, CinemaEmber.FocusRing),
                shape = RoundedCornerShape(CinemaEmber.RadiusControl),
            ),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = CinemaEmber.BgSurface,
            focusedContainerColor = CinemaEmber.BgSurfaceHover,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = CinemaEmber.TextPrimary,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = CinemaEmber.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = CinemaEmber.Accent,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
