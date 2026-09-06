package com.romm.desktop.controller

import com.romm.desktop.log.DesktopLogger
import net.java.games.input.ControllerEnvironment
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Windows / non-Linux [ControllerEnvironmentPolicy] (Phase 1).
 *
 * Uses JInput's default environment and **never references or loads the `LinuxEnvironmentPlugin`**
 * (this file has no such import, and the class is only ever constructed for non-Linux hosts).
 *
 * JInput's `DefaultControllerEnvironment` caches its controller list after the first scan and
 * exposes no public re-scan, so a failed poll cannot discover a (re)connected controller on its own.
 * [refresh] performs a bounded, *meaningful* re-enumeration: it clears the environment's cached
 * controller list so the next [ControllerEnvironment.getControllers] call re-scans the platform
 * plugin. The attempt is rate-limited by a cooldown window and logged at INFO (never WARNING), so a
 * persistent poll failure re-enumerates on a bounded cadence instead of warning forever.
 */
class DefaultControllerEnvironmentPolicy(
    environmentOverride: ControllerEnvironment? = null,
) : ControllerEnvironmentPolicy {

    private val logger: Logger by lazy { DesktopLogger.get() }

    /**
     * The injected [environmentOverride] (a test fake) is used as-is so no JInput native is loaded;
     * otherwise the real default environment is obtained lazily on first access.
     */
    override val environment: ControllerEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        environmentOverride ?: ControllerEnvironment.getDefaultEnvironment()
    }

    private var nextRescanAllowedNanos = 0L

    override fun refresh(): Boolean {
        val now = System.nanoTime()
        if (now < nextRescanAllowedNanos) return false
        nextRescanAllowedNanos = now + RESCAN_COOLDOWN_NANOS
        return try {
            // DefaultControllerEnvironment.getControllers() re-runs its plugin scan only while its
            // `controllers` list is null, so clearing the field forces a real re-enumeration.
            val controllersField = environment.javaClass.getDeclaredField("controllers")
            controllersField.isAccessible = true
            controllersField.set(environment, null)
            logger.info("JInput controller poll failed; re-enumerating the default environment")
            true
        } catch (e: ReflectiveOperationException) {
            logger.log(
                Level.WARNING,
                "JInput controller poll failed and the environment could not be re-enumerated",
                e,
            )
            false
        }
    }

    /** Non-Linux hosts have no `/dev/input` topology signal; failed polls drive recovery instead. */
    override fun topologySnapshot(): Set<String>? = null

    /** No platform-specific diagnostics on the default environment. */
    override fun diagnostics(names: List<String>): List<String> = emptyList()

    private companion object {
        /** Bounds the re-enumeration so a persistent poll failure cannot hammer the environment. */
        const val RESCAN_COOLDOWN_NANOS = 2_000_000_000L
    }
}
