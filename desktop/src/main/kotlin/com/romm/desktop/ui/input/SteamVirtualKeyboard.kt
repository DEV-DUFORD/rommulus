package com.romm.desktop.ui.input

import com.romm.desktop.log.DesktopLogger
import java.io.IOException
import java.util.Locale
import java.util.logging.Level

private val STEAM_KEYBOARD_COMMAND = listOf("steam", "-ifrunning", "steam://open/keyboard")

internal fun openSteamVirtualKeyboard(
    osName: String = System.getProperty("os.name"),
    start: (List<String>) -> Unit = ::startSteamCommand,
): Boolean {
    if (!osName.lowercase(Locale.ROOT).contains("linux")) return false
    return try {
        start(STEAM_KEYBOARD_COMMAND)
        true
    } catch (error: IOException) {
        DesktopLogger.get().log(Level.WARNING, "Could not open the Steam virtual keyboard", error)
        false
    } catch (error: SecurityException) {
        DesktopLogger.get().log(Level.WARNING, "Steam virtual keyboard launch was denied", error)
        false
    }
}

private fun startSteamCommand(command: List<String>) {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
