package com.romm.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.romm.desktop.log.DesktopLogger
import com.romm.desktop.storage.FileLockAppInstanceLock
import com.romm.desktop.storage.paths.XdgAppPaths
import com.romm.desktop.storage.secret.FileSecretBackend
import com.romm.desktop.storage.secret.UnavailableSecretServiceFallback
import com.romm.desktop.storage.secret.dbus.SecretServiceDbusBackend
import com.romm.desktop.ui.image.loadBundledImage
import java.util.logging.Level

/**
 * Desktop entry point: builds the real XDG [XdgAppPaths], the freedesktop Secret Service
 * backend, and the [DesktopAppCoordinator], then hosts the Compose shell in a Window.
 *
 * Single-instance enforcement (plans/LINUX_X64.md §10.4): if the advisory file lock cannot
 * be acquired, another instance is already running — print an explanatory message and exit.
 */
fun main() {
    val desktopEnvironment = System.getenv()
    val displayPolicy = desktopDisplayPolicy(desktopEnvironment)

    application {
        val paths = XdgAppPaths()
        val lock = FileLockAppInstanceLock(null, paths.stateDir)
        if (!lock.acquire()) {
            println("RomMulus is already running. Only one instance may run at a time; exiting.")
            return@application
        }

        val coordinator = DesktopAppCoordinator(
            paths = paths,
            secretBackend = UnavailableSecretServiceFallback(
                primary = SecretServiceDbusBackend(),
                fallback = FileSecretBackend(
                    paths.stateDir.resolve("credentials").resolve("client-tokens.properties"),
                ),
            ),
            appVersion = APP_VERSION,
            buildDefaultOrigin = BUILD_DEFAULT_ORIGIN,
            desktopEnvironment = desktopEnvironment,
        )
        // Phase 8 Wave 2: crash-recovery scan over incomplete player launch journals
        // (plans/LINUX_X64.md §12.5). Runs before the first composition; diagnostics are logged now
        // and will be surfaced in the launch screen UI (Phase 8 Wave 3+).
        coordinator.scanPlayerJournals().forEach { diagnostic ->
            // The format-specifier path: without "%s" this binds to log(Level, String, Object) and
            // `diagnostic.summary` is silently dropped (the tag becomes the whole message).
            DesktopLogger.get().log(Level.WARNING, "PlayerJournal: %s", diagnostic.summary)
        }

        // Select the root AppMode synchronously before the first composition so onboarding never
        // flashes Home (mirrors MainActivity).
        coordinator.appMode = coordinator.computeStartupAppMode()

        // Window icon: the bundled RomMulus mark (desktop port of Android's ic_launcher.xml),
        // rasterized once from the classpath SVG. `null` keeps Compose's default icon if the
        // asset is ever missing.
        val windowIcon: Painter? = remember {
            loadBundledImage("/icons/rommulus_icon.svg", size = 256)?.let(::BitmapPainter)
        }
        val windowState = rememberWindowState(
            placement = if (displayPolicy.fullscreen) {
                WindowPlacement.Fullscreen
            } else {
                WindowPlacement.Floating
            },
            width = 1280.dp,
            height = 720.dp,
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "RomMulus",
            state = windowState,
            icon = windowIcon,
            undecorated = displayPolicy.undecorated,
        ) {
            RommulusDesktopApp(
                coordinator = coordinator,
                onCloseRequest = ::exitApplication,
            )
        }
    }
}

private const val APP_VERSION = "0.1.0-desktop"
private const val BUILD_DEFAULT_ORIGIN = "https://demo.romm.app"
