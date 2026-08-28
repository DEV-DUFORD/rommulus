package com.romm.desktop

internal data class DesktopDisplayPolicy(
    val fullscreen: Boolean = false,
    val undecorated: Boolean = false,
)

internal fun desktopDisplayPolicy(environment: Map<String, String>): DesktopDisplayPolicy =
    environment["XDG_CURRENT_DESKTOP"]
        ?.contains("gamescope", ignoreCase = true)
        ?.let { gamescope ->
            DesktopDisplayPolicy(
                fullscreen = gamescope,
                undecorated = gamescope,
            )
        }
        ?: DesktopDisplayPolicy()
