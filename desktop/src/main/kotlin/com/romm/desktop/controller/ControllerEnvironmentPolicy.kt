package com.romm.desktop.controller

import com.romm.desktop.platform.HostOs
import net.java.games.input.ControllerEnvironment

/**
 * Platform-selected strategy for the JInput controller environment (Phase 1).
 *
 * The production [JInputControllerSource] delegates every platform-specific concern to this
 * policy so its portable mapping / cache / dedup logic stays platform-neutral. The policy is
 * selected from the already-normalized host result ([HostOs]) via [forHostOs] — feature code
 * never sniffs `os.name` itself.
 *
 * Two implementations exist:
 * - [LinuxControllerEnvironmentPolicy]: solely owns the `LinuxEnvironmentPlugin` (kept as the
 *   live environment so its cached device list can be refreshed in place), the reflection-based
 *   device refresh, and the `/dev/input` topology / readability diagnostics.
 * - [DefaultControllerEnvironmentPolicy]: the Windows/non-Linux path — uses JInput's default
 *   environment, never references or loads the Linux plugin, and performs a bounded, meaningful
 *   re-enumeration after a poll failure instead of warning forever.
 */
interface ControllerEnvironmentPolicy {

    /** The JInput environment to enumerate controllers from. */
    val environment: ControllerEnvironment

    /**
     * Attempt a bounded re-enumeration of [environment]. Returns `true` when the environment was
     * actually re-scanned (so the caller should drop its cached wrappers) and `false` when the
     * attempt was suppressed by the cooldown window or could not run. A suppressed attempt must
     * NOT log a WARNING — a persistent poll failure must not warn forever.
     */
    fun refresh(): Boolean

    /**
     * The input-node topology snapshot used to detect hot-plug, or `null` when this platform has
     * no portable topology signal (non-Linux). The caller owns the change detection over the
     * returned set.
     */
    fun topologySnapshot(): Set<String>?

    /**
     * Platform-specific diagnostics for a detected [names] set, as log messages. Returns an empty
     * list when there is nothing to report. The caller logs each returned message at WARNING.
     */
    fun diagnostics(names: List<String>): List<String>

    companion object {
        /**
         * Selects the policy for the normalized [hostOs]. Only [HostOs.LINUX] gets the
         * `LinuxEnvironmentPlugin`-backed policy; every other host (Windows, macOS, unknown) uses
         * the default JInput environment and never touches the Linux plugin.
         */
        fun forHostOs(hostOs: HostOs): ControllerEnvironmentPolicy =
            if (hostOs == HostOs.LINUX) {
                LinuxControllerEnvironmentPolicy()
            } else {
                DefaultControllerEnvironmentPolicy()
            }
    }
}
