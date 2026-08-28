package com.romm.androidtv.controller.config

/**
 * Metadata contract for a controller illustration asset.
 *
 * Example: resourceName = "controller_outline_snes", source = "Controllercons 2.1 outline",
 * license = "SIL Open Font License 1.1".
 */
data class ControllerArtwork(
    val resourceName: String,
    val source: String,
    val license: String,
    val licenseAssetPath: String?,
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
) {
    init {
        require(resourceName.isNotBlank()) { "resourceName must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        require(license.isNotBlank()) { "license must not be blank" }
        require(viewBoxWidth > 0f) { "viewBoxWidth must be positive, was $viewBoxWidth" }
        require(viewBoxHeight > 0f) { "viewBoxHeight must be positive, was $viewBoxHeight" }
    }
}
