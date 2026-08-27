package com.romm.desktop.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private const val DESIGN_WIDTH = 1280f
private const val DESIGN_HEIGHT = 800f

val LocalDesktopUiScale = compositionLocalOf { 1f }

/**
 * Scales the desktop UI from its 1280 x 800 design baseline. The shorter window axis controls
 * the scale so layouts retain their aspect and remain fully visible.
 */
@Composable
fun DesktopScaledContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val baseDensity = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val scale = desktopUiScale(maxWidth.value, maxHeight.value)
        val scaledDensity = remember(baseDensity, scale) {
            Density(
                density = baseDensity.density * scale,
                fontScale = baseDensity.fontScale,
            )
        }
        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalDesktopUiScale provides scale,
            content = content,
        )
    }
}

/**
 * Re-applies the parent window's scale in dialog/window compositions that do not inherit locals.
 * If the dialog already inherited the scale, its density is left unchanged.
 */
@Composable
fun DesktopScaledDialogContent(
    scale: Float,
    content: @Composable () -> Unit,
) {
    val inheritedScale = LocalDesktopUiScale.current
    val baseDensity = LocalDensity.current
    val density = remember(baseDensity, inheritedScale, scale) {
        if (inheritedScale == scale) {
            baseDensity
        } else {
            Density(
                density = baseDensity.density * scale,
                fontScale = baseDensity.fontScale,
            )
        }
    }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalDesktopUiScale provides scale,
        content = content,
    )
}

internal fun desktopUiScale(width: Float, height: Float): Float =
    minOf(width / DESIGN_WIDTH, height / DESIGN_HEIGHT).coerceAtLeast(1f)
