package com.romm.androidtv.emulation.model

/**
 * First-class native build identities for the standalone desktop player (plans/LINUX_X64.md §13.1,
 * plans/WINDOWS_IMPL.md §3.1).
 *
 * These are NOT Android ABIs: they identify the host platform a native core library is built for,
 * and they appear in [CoreLicenseFinding.supportedAbis] alongside the Android ABIs from
 * [ANDROID_CORE_ABIS]. An advertised identity enables core selection on that platform.
 * The experimental branch directly enables thirteen game cores for [WINDOWS_X86_64];
 * physical Windows 10/11 qualification remains pending and is recorded separately in
 * docs/windows-support-manifest.md. Enablement does not imply hardware validation.
 */
object NativeBuildIdentities {
    /** Linux x86_64 desktop build identity (`lib*.so` cores, `rommulus_player`). */
    const val LINUX_X86_64 = "linux-x86_64"

    /** Windows x86_64 desktop build identity (`<core-id>_core.dll` cores, `rommulus-player.exe`). */
    const val WINDOWS_X86_64 = "windows-x86_64"

    /** All production native build identities. */
    val PRODUCTION: Set<String> = setOf(LINUX_X86_64, WINDOWS_X86_64)
}
