package com.romm.desktop

internal data class DesktopDisplayPolicy(
    val fullscreen: Boolean = false,
)

internal fun desktopDisplayPolicy(environment: Map<String, String>): DesktopDisplayPolicy =
    DesktopDisplayPolicy(
        fullscreen = environment["XDG_CURRENT_DESKTOP"]
            ?.contains("gamescope", ignoreCase = true)
            ?: false,
    )
