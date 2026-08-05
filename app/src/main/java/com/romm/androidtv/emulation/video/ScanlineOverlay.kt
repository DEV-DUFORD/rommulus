package com.romm.androidtv.emulation.video

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

private const val SCANLINE_ALPHA = 0.30f
private const val SCANLINE_HEIGHT_FRACTION = 0.38f
private const val MIN_SCANLINE_HEIGHT_PX = 1f
private const val MAX_SCANLINE_HEIGHT_PX = 3f

internal data class ScanlineBand(
    val topPx: Float,
    val heightPx: Float,
)

internal fun calculateScanlineBands(
    canvasHeightPx: Float,
    coreHeight: Int,
): List<ScanlineBand> {
    if (canvasHeightPx <= 0f || coreHeight <= 0) return emptyList()

    val sourceRowHeight = canvasHeightPx / coreHeight

    // Fallback: source rows are too small to render individually. Draw a
    // deterministic 1px-dark-line every 2 output pixels.
    if (sourceRowHeight < 1f) {
        val bands = ArrayList<ScanlineBand>((canvasHeightPx / 2f).toInt() + 1)
        var y = 0f
        while (y < canvasHeightPx) {
            bands.add(ScanlineBand(topPx = y, heightPx = MIN_SCANLINE_HEIGHT_PX))
            y += 2f
        }
        return bands
    }

    val lineHeight = (sourceRowHeight * SCANLINE_HEIGHT_FRACTION)
        .coerceIn(MIN_SCANLINE_HEIGHT_PX, MAX_SCANLINE_HEIGHT_PX)

    val bands = ArrayList<ScanlineBand>(coreHeight / 2 + 1)
    var i = 0
    while (i < coreHeight) {
        val rowTop = i * sourceRowHeight
        val bandTop = rowTop + (sourceRowHeight - lineHeight) / 2f

        // Clip band top to the valid canvas range.
        val clampedTop = bandTop.coerceIn(0f, canvasHeightPx - lineHeight)
        if (clampedTop <= canvasHeightPx - lineHeight) {
            // Round to the nearest whole pixel so band boundaries are stable on
            // physical displays (avoids sub-pixel shimmer). Deterministic.
            bands.add(
                ScanlineBand(
                    // kotlin.math.round uses banker's rounding (ties to even),
                    // e.g. 48.5 -> 48f, keeping band boundaries stable.
                    topPx = kotlin.math.round(clampedTop).toInt().toFloat(),
                    heightPx = lineHeight,
                ),
            )
        }

        i += 2
    }

    return bands
}

@Composable
internal fun ScanlineOverlay(
    coreHeight: Int,
    modifier: Modifier = Modifier,
) {
    // Purely decorative. No clickable/focusable/pointer/accessibility semantics.
    Canvas(
        modifier = modifier.drawWithCache {
            // Recompute the bands only when the canvas size or coreHeight
            // changes — not on every recomposition frame.
            val bands = calculateScanlineBands(size.height, coreHeight)

            onDrawBehind {
                if (bands.isEmpty()) return@onDrawBehind
                val width = size.width
                val color = Color.Black.copy(alpha = SCANLINE_ALPHA)
                for (band in bands) {
                    drawRect(
                        color = color,
                        topLeft = Offset(x = 0f, y = band.topPx),
                        size = Size(width = width, height = band.heightPx),
                    )
                }
            }
        },
    ) { /* content intentionally empty — overlay draws in the cached layer */ }
}
