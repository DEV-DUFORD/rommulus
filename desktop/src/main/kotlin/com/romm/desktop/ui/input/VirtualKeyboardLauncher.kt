package com.romm.desktop.ui.input

import com.romm.desktop.log.DesktopLogger
import com.romm.desktop.platform.HostOs
import java.io.IOException
import java.util.logging.Level

/**
 * Platform-selected strategy for launching the on-screen virtual keyboard (Phase 1).
 *
 * The search field's activate action used to call a top-level helper that sniffed `os.name`
 * directly. Feature code now depends on this seam instead: the coordinator exposes a
 * [VirtualKeyboardLauncher] selected from the already-normalized host result ([HostOs]) via
 * [forHostOs], so no feature code reads `os.name` itself.
 *
 * - [SteamVirtualKeyboardLauncher]: Linux — opens the Steam on-screen keyboard via its URI scheme.
 * - [NoopVirtualKeyboardLauncher]: Windows / non-Linux — no on-screen keyboard is available.
 */
interface VirtualKeyboardLauncher {

    /**
     * Launches the virtual keyboard. Returns `true` when a launch was attempted and `false` when
     * there is no keyboard to launch (or the attempt was denied/failed).
     */
    fun launch(): Boolean

    companion object {
        /**
         * Selects the launcher for the normalized [hostOs]. Only [HostOs.LINUX] gets the Steam
         * launcher; every other host (Windows, macOS, unknown) is a no-op.
         */
        fun forHostOs(hostOs: HostOs): VirtualKeyboardLauncher =
            if (hostOs == HostOs.LINUX) {
                SteamVirtualKeyboardLauncher()
            } else {
                NoopVirtualKeyboardLauncher
            }
    }
}

/**
 * Linux virtual keyboard launcher: opens the Steam on-screen keyboard via its URI scheme. The
 * [start] seam is injectable so tests can observe the command without spawning a process.
 */
class SteamVirtualKeyboardLauncher(
    private val start: (List<String>) -> Unit = ::startSteamCommand,
) : VirtualKeyboardLauncher {
    override fun launch(): Boolean = try {
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

/** Non-Linux virtual keyboard launcher: there is no on-screen keyboard to open. */
object NoopVirtualKeyboardLauncher : VirtualKeyboardLauncher {
    override fun launch(): Boolean = false
}

private val STEAM_KEYBOARD_COMMAND = listOf("steam", "-ifrunning", "steam://open/keyboard")

private fun startSteamCommand(command: List<String>) {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
