package com.romm.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.romm.desktop.log.DesktopLogger
import com.romm.desktop.platform.DesktopPlatformDetector
import com.romm.desktop.platform.DesktopStartupPlan
import com.romm.desktop.platform.desktopHostAdapters
import com.romm.desktop.platform.desktopStartupPlan
import com.romm.desktop.platform.WindowsNativeBundle
import com.romm.desktop.storage.FileLockAppInstanceLock
import com.romm.desktop.ui.image.loadBundledImage
import java.util.logging.Level
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * Desktop entry point: detects the host platform ONCE, derives one coherent host-adapter bundle
 * from that single [com.romm.desktop.platform.PlatformDetectionResult] (paths, file-security
 * policy, credential backend, artifact layout, controller policy, virtual keyboard), installs
 * the logger under the selected paths, and hosts the Compose shell in a Window.
 *
 * Host detection (plans/WINDOWS_IMPL.md §3.1) runs before any platform wiring: an unsupported
 * host fails fast with a clear diagnostic instead of silently running Linux XDG
 * paths/credentials. macOS is an explicit development-only host that proceeds with the
 * Linux-compatible dev wiring; Windows x86_64 proceeds on its own Known Folder / NTFS ACL /
 * Credential Manager adapters (Phase 1).
 *
 * Single-instance enforcement (plans/LINUX_X64.md §10.4): exactly ONE [FileLockAppInstanceLock]
 * is built here and injected into the coordinator; if the advisory file lock cannot be acquired,
 * another instance is already running — print an explanatory message and exit.
 */
fun main(args: Array<String>) {
    val detection = DesktopPlatformDetector.detectHost()

    // Startup gate (plans/WINDOWS_IMPL.md §3.1): unsupported hosts fail before any adapter is
    // constructed or any platform wiring exists.
    val startupPlan = desktopStartupPlan(detection)
    if (startupPlan !is DesktopStartupPlan.Proceed) {
        System.err.println((startupPlan as DesktopStartupPlan.FailFast).message)
        exitProcess(1)
    }

    // One coherent host-adapter bundle from the single detection result: XDG + D-Bus/file
    // credentials on Linux and the macOS development-only path; Known Folder paths + NTFS ACL
    // policy + Credential Manager on Windows (JNA natives stay lazy until first use).
    val adapters = desktopHostAdapters(detection)

    // Logger first: install from the selected AppPaths before ordinary logging begins, so every
    // record lands under this host's real state dir (plans/WINDOWS_IMPL.md §4.4).
    DesktopLogger.install(adapters.appPaths, adapters.securityPolicy)
    val nativeBundle = WindowsNativeBundle.fromLauncher(adapters.layout)
    nativeBundle?.verify()
    val smokeReport = if (args.size == 2 && args[0] == "--smoke-report") Path.of(args[1]) else null
    require(args.isEmpty() || smokeReport != null) { "Usage: RomMulus [--smoke-report <file>]" }
    startupPlan.note?.let { note ->
        // The "%s" format specifier binds log(Level, String, Object); without it the notice is
        // silently dropped (see the PlayerJournal logging below).
        DesktopLogger.get().log(Level.INFO, "Platform: %s", note)
    }

    val desktopEnvironment = System.getenv()
    val displayPolicy = desktopDisplayPolicy(desktopEnvironment)

    application {
        // The process's single instance lock; injected into the coordinator so exactly one
        // FileLockAppInstanceLock exists for this run (plans/LINUX_X64.md §10.4).
        val appInstanceLock = FileLockAppInstanceLock(null, adapters.appPaths.stateDir)
        if (!appInstanceLock.acquire()) {
            println("RomMulus is already running. Only one instance may run at a time; exiting.")
            return@application
        }

        val coordinator = DesktopAppCoordinator(
            paths = adapters.appPaths,
            secretBackend = adapters.secretBackend,
            appVersion = APP_VERSION,
            buildDefaultOrigin = BUILD_DEFAULT_ORIGIN,
            // Layout / host / controller / keyboard all come from the one bundle — never
            // re-detected here (plans/WINDOWS_IMPL.md §3.1).
            layout = adapters.layout,
            hostOs = adapters.hostOs,
            controllerEnvironmentPolicy = adapters.controllerEnvironmentPolicy,
            virtualKeyboardLauncher = adapters.virtualKeyboardLauncher,
            appInstanceLock = appInstanceLock,
            desktopEnvironment = desktopEnvironment,
            nativeBundle = nativeBundle,
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
            if (smokeReport != null) {
                LaunchedEffect(Unit) {
                    delay(1500)
                    Files.writeString(smokeReport, "ROMMULUS_DESKTOP_SMOKE_PASS\n")
                    exitApplication()
                }
            }
            RommulusDesktopApp(
                coordinator = coordinator,
                onCloseRequest = ::exitApplication,
            )
        }
    }
}

private const val APP_VERSION = "0.3.0"
private const val BUILD_DEFAULT_ORIGIN = "https://demo.romm.app"
