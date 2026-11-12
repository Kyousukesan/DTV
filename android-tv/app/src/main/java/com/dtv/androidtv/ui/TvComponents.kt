package com.dtv.androidtv.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Shared dark gradient backdrop for browse / search / settings. */
@Composable
fun TvScreenBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1020), Color(0xFF16213E), Color(0xFF0B1020)),
                ),
            ),
    ) {
        content()
    }
}

/**
 * Primary DPAD action chip used in headers and chrome.
 * Focus scale + focused container keep the ring readable at 10-foot.
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
        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = idleColor,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = TvLayout.ButtonPaddingH,
                vertical = TvLayout.ButtonPaddingV,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
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
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
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
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
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
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
