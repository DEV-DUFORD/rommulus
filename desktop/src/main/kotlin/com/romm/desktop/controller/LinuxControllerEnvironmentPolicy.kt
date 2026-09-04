package com.romm.desktop.controller

import com.romm.desktop.log.DesktopLogger
import net.java.games.input.ControllerEnvironment
import net.java.games.input.LinuxEnvironmentPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Linux [ControllerEnvironmentPolicy] (Phase 1).
 *
 * This is the ONLY place that references or instantiates JInput's `LinuxEnvironmentPlugin`. It is
 * kept as the live [environment] (rather than behind `DefaultControllerEnvironment`, which discards
 * the plugin after copying its controllers) so the plugin's cached device list can be refreshed in
 * place when a poll fails or the input topology changes. It also solely owns the `/dev/input`
 * topology snapshot (the hot-plug signal) and the "detected but unreadable" readability diagnostic.
 */
class LinuxControllerEnvironmentPolicy(
    environmentOverride: ControllerEnvironment? = null,
) : ControllerEnvironmentPolicy {

    private val logger: Logger = DesktopLogger.get()

    /**
     * The `LinuxEnvironmentPlugin` is the live environment. [JInputControllerSource.ensureJinputNatives]
     * (run before the first [environment] access) makes the native loadable; the plugin loads it in
     * its own initialization when first enumerated. A test may inject a fake [environmentOverride]
     * to exercise the topology / diagnostic paths without loading the native.
     */
    override val environment: ControllerEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        environmentOverride ?: LinuxEnvironmentPlugin()
    }

    private var nextRescanAllowedNanos = 0L

    override fun refresh(): Boolean {
        val now = System.nanoTime()
        if (now < nextRescanAllowedNanos) return false
        nextRescanAllowedNanos = now + RESCAN_COOLDOWN_NANOS
        if (environment !is LinuxEnvironmentPlugin) {
            logger.warning("JInput hot-plug refresh is not available on this platform")
            return false
        }
        return try {
            val pluginClass = LinuxEnvironmentPlugin::class.java
            val devicesField = pluginClass.getDeclaredField("devices")
            devicesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val devices = devicesField.get(environment) as MutableList<Any>
            for (device in devices.toList()) {
                device.javaClass.getMethod("close").apply { isAccessible = true }.invoke(device)
            }
            devices.clear()

            val enumerateMethod = pluginClass.getDeclaredMethod("enumerateControllers")
            enumerateMethod.isAccessible = true
            val controllers = enumerateMethod.invoke(environment) as Array<*>
            val controllersField = pluginClass.getDeclaredField("controllers")
            controllersField.isAccessible = true
            controllersField.set(environment, controllers)
            logger.info("JInput controller set changed or became unavailable; rescanning devices")
            true
        } catch (e: ReflectiveOperationException) {
            logger.log(
                Level.WARNING,
                "JInput controller poll failed and the device cache could not be refreshed",
                e,
            )
            false
        }
    }

    /**
     * JInput caches its Linux device list, so a newly connected controller cannot be discovered
     * unless an existing controller first fails a poll. Watching the input-node names supplies the
     * missing reconnect signal.
     */
    override fun topologySnapshot(): Set<String>? {
        return try {
            val inputDir = Path.of("/dev/input")
            if (!Files.isDirectory(inputDir)) return null
            Files.newDirectoryStream(inputDir).use { devices ->
                devices.mapNotNullTo(sortedSetOf()) { device ->
                    device.fileName.toString().takeIf { name ->
                        name.startsWith("event") || name.startsWith("js")
                    }
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * "Detected but unreadable" diagnostic: JInput enumerates controllers from `/dev/input/event*`
     * but cannot open them when this process lacks read permission (open() fails with EACCES) —
     * typically because the user is not in the `input` group.
     */
    override fun diagnostics(names: List<String>): List<String> {
        if (names.isEmpty() || inputEventDevicesReadable() != false) return emptyList()
        return listOf(
            "Controllers were detected but no /dev/input/event* device is readable " +
                "by this process (opening them fails with permission denied). " +
                "Add your user to the 'input' group: sudo usermod -aG input \$USER, " +
                "then log out and back in.",
        )
    }

    /**
     * Best-effort readability probe: returns true if at least one `/dev/input/event*` device node is
     * readable by this process, false if event devices exist but NONE are readable, and null if the
     * probe cannot run (`/dev/input` missing, no event nodes, or any I/O failure). [Files.isReadable]
     * evaluates the permission bits against the current uid/gid — the same check that makes open()
     * fail with EACCES — so it faithfully predicts JInput's open failures.
     */
    private fun inputEventDevicesReadable(): Boolean? {
        return try {
            val inputDir = Path.of("/dev/input")
            if (!Files.isDirectory(inputDir)) return null
            var found = false
            var anyReadable = false
            Files.newDirectoryStream(inputDir, "event*").use { devices ->
                for (device in devices) {
                    found = true
                    if (Files.isReadable(device)) {
                        anyReadable = true
                        break
                    }
                }
            }
            if (!found) null else anyReadable
        } catch (t: Throwable) {
            null
        }
    }

    private companion object {
        /** Bounds the Linux device refresh so a persistent poll failure cannot hammer the plugin. */
        const val RESCAN_COOLDOWN_NANOS = 2_000_000_000L
    }
}
