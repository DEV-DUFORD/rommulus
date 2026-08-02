package com.romm.androidtv.controller.config

/**
 * Shape of a highlight region overlaid on the controller artwork.
 */
enum class HighlightShape {
    CIRCLE,
    RECT,
    OVAL,
}

/**
 * A rectangular or circular region used to highlight a control on the controller illustration.
 *
 * All coordinates are **normalized fractions** of the artwork viewBox (0..1). Stable region
 * IDs match the artwork annotation layer so the UI can highlight the correct region on focus.
 */
data class ControllerHighlightRegion(
    val id: String,
    val shape: HighlightShape,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float = 0f,
) {
    init {
        require(x in 0f..1f) { "x must be in [0, 1], was $x" }
        require(y in 0f..1f) { "y must be in [0, 1], was $y" }
        require(width > 0f) { "width must be positive, was $width" }
        require(height > 0f) { "height must be positive, was $height" }
        require(x + width <= 1.0001f) { "x + width must be <= 1.0 (got ${x + width})" }
        require(y + height <= 1.0001f) { "y + height must be <= 1.0 (got ${y + height})" }
    }
}
