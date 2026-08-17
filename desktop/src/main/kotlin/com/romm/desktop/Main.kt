package com.romm.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.romm.desktop.log.DesktopLogger
import com.romm.desktop.storage.FileLockAppInstanceLock
import com.romm.desktop.storage.paths.XdgAppPaths
import com.romm.desktop.storage.secret.dbus.SecretServiceDbusBackend
import java.util.logging.Level

/**
 * Desktop entry point: builds the real XDG [XdgAppPaths], the freedesktop Secret Service
 * backend, and the [DesktopAppCoordinator], then hosts the Compose shell in a Window.
 *
 * Single-instance enforcement (plans/LINUX_X64.md §10.4): if the advisory file lock cannot
 * be acquired, another instance is already running — print an explanatory message and exit.
 */
fun main() = application {
    val paths = XdgAppPaths()
    val lock = FileLockAppInstanceLock(null, paths.stateDir)
    if (!lock.acquire()) {
        println("RomMulus is already running. Only one instance may run at a time; exiting.")
        return@application
    }

    val coordinator = DesktopAppCoordinator(
        paths = paths,
        secretBackend = SecretServiceDbusBackend(),
        appVersion = APP_VERSION,
        buildDefaultOrigin = BUILD_DEFAULT_ORIGIN,
    )
    // Phase 8 Wave 2: crash-recovery scan over incomplete player launch journals
    // (plans/LINUX_X64.md §12.5). Runs before the first composition; diagnostics are logged now
    // and will be surfaced in the launch screen UI (Phase 8 Wave 3+).
    coordinator.scanPlayerJournals().forEach { diagnostic ->
        DesktopLogger.get().log(Level.WARNING, "PlayerJournal", diagnostic.summary)
    }

    // Select the root AppMode synchronously before the first composition so onboarding never
    // flashes Home (mirrors MainActivity).
    coordinator.appMode = coordinator.computeStartupAppMode()

    Window(
        onCloseRequest = ::exitApplication,
        title = "RomMulus",
        state = WindowState(width = 1280.dp, height = 720.dp),
    ) {
        RommulusDesktopApp(
            coordinator = coordinator,
            onCloseRequest = ::exitApplication,
        )
    }
}

private const val APP_VERSION = "0.1.0-desktop"
private const val BUILD_DEFAULT_ORIGIN = "https://demo.romm.app"
