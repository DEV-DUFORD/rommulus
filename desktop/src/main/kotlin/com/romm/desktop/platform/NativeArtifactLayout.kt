package com.romm.desktop.platform

import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.NativeBuildIdentities
import java.nio.file.Files
import java.nio.file.Path

/**
 * Platform-specific on-disk layout for the native player and its core libraries — executable
 * name, core library naming, and installed-core scanning (plans/WINDOWS_IMPL.md §4.1).
 *
 * This is the single place that knows how a [buildIdentity] maps to file names on disk. The
 * desktop launch code ([com.romm.desktop.player.ProcessBuilderPlayerLauncher]) and core
 * resolution ([com.romm.desktop.DesktopAppCoordinator]) consume this interface instead of
 * hard-coding Linux `lib*.so` / `rommulus_player` spellings.
 */
interface NativeArtifactLayout {
    /** The first-class native build identity this layout targets (never an Android ABI). */
    val buildIdentity: String

    /** Player executable file name (no directory), e.g. `rommulus_player` or `rommulus-player.exe`. */
    val playerExecutableName: String

    /** On-disk core library file names that may carry [coreId], in preference order. */
    fun coreLibraryFileNames(coreId: String): List<String>

    /**
     * Scans [coresDir] for installed core libraries and returns the extracted core ids, sorted
     * for determinism. A missing (or non-directory) [coresDir] yields an empty list.
     */
    fun scanInstalledCoreIds(coresDir: Path): List<String>

    /**
     * Resolves the on-disk core library for [coreId] under [coresDir]: the first existing
     * candidate from [coreLibraryFileNames]. When nothing is installed, falls back to the
     * canonical name so the player rejects the request with a clear missing-file error instead
     * of the desktop failing to compose a path.
     */
    fun resolveCoreLibraryPath(coresDir: Path, coreId: String): Path

    /** Derives the `ROMM_PLAYER_ALLOWED_CORES` value for this layout's [buildIdentity]. */
    fun deriveAllowedCores(installedCoreIds: Collection<String>): String =
        deriveAllowedCoresForIdentity(installedCoreIds, buildIdentity)
}

/**
 * Linux x86_64 artifact layout — the historical desktop behavior, extracted unchanged:
 * `rommulus_player` executable, `lib<coreId>.so` / `lib<coreId>_core.so` core libraries.
 */
object LinuxNativeArtifactLayout : NativeArtifactLayout {
    override val buildIdentity: String = NativeBuildIdentities.LINUX_X86_64

    override val playerExecutableName: String = "rommulus_player"

    /**
     * CMake names core targets `<coreId>_core` (e.g. `gambatte_core` → `libgambatte_core.so`),
     * but the synthetic `test_core` target is named `test_core` itself (→ `libtest_core.so`), so
     * both spellings are accepted wherever a core library is resolved.
     */
    override fun coreLibraryFileNames(coreId: String): List<String> =
        listOf("lib$coreId.so", "lib${coreId}_core.so")

    /**
     * Scans for `lib*.so` regular files. Extraction: strip the `lib` prefix and `.so` suffix,
     * then a trailing `_core` CMake target suffix (`libgambatte_core.so` → `gambatte`). Note the
     * `_core` strip is lossy for the synthetic `test_core` (`libtest_core.so` → `test`);
     * [deriveAllowedCores] resolves that ambiguity against [CoreManifest].
     */
    override fun scanInstalledCoreIds(coresDir: Path): List<String> {
        if (!Files.isDirectory(coresDir)) return emptyList()
        val names = Files.list(coresDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { it.startsWith("lib") && it.endsWith(".so") }
                .toList()
        }
        return names
            .map { it.removePrefix("lib").removeSuffix(".so").removeSuffix("_core") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    override fun resolveCoreLibraryPath(coresDir: Path, coreId: String): Path =
        coresDir.resolve(
            coreLibraryFileNames(coreId).firstOrNull { Files.exists(coresDir.resolve(it)) }
                ?: "lib$coreId.so",
        )
}

/**
 * Windows x86_64 artifact layout (plans/WINDOWS_IMPL.md §2.2/§3.3): `rommulus-player.exe`
 * executable and `<core-id>_core.dll` core libraries under the package's `native/cores/` root.
 *
 * Naming is defined now, in Phase 0, without enabling any production core: no manifest entry
 * advertises [NativeBuildIdentities.WINDOWS_X86_64], so [deriveAllowedCores] yields an empty
 * allowlist until a core passes its Windows gate (plans/WINDOWS_IMPL.md §6.4).
 */
object WindowsNativeArtifactLayout : NativeArtifactLayout {
    override val buildIdentity: String = NativeBuildIdentities.WINDOWS_X86_64

    override val playerExecutableName: String = "rommulus-player.exe"

    /**
     * Canonical name is `<core-id>_core.dll` (the CMake target spelling, e.g.
     * `gambatte_core.dll`); `<core-id>.dll` is a controlled compatibility alias mirroring how
     * the Linux layout accepts both `lib<id>.so` and `lib<id>_core.so`.
     */
    override fun coreLibraryFileNames(coreId: String): List<String> =
        listOf("${coreId}_core.dll", "$coreId.dll")

    /**
     * Scans for `*.dll` regular files. Extraction: strip the `.dll` suffix, then a trailing
     * `_core` CMake target suffix (`gambatte_core.dll` → `gambatte`). As on Linux the `_core`
     * strip is lossy for the synthetic `test_core` (`test_core.dll` → `test`);
     * [deriveAllowedCores] resolves that ambiguity against [CoreManifest].
     */
    override fun scanInstalledCoreIds(coresDir: Path): List<String> {
        if (!Files.isDirectory(coresDir)) return emptyList()
        val names = Files.list(coresDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { it.lowercase().endsWith(".dll") }
                .toList()
        }
        return names
            .map { stripDllSuffix(it).removeSuffix("_core") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    override fun resolveCoreLibraryPath(coresDir: Path, coreId: String): Path =
        coresDir.resolve(
            coreLibraryFileNames(coreId).firstOrNull { Files.exists(coresDir.resolve(it)) }
                ?: "${coreId}_core.dll",
        )

    private fun stripDllSuffix(fileName: String): String =
        if (fileName.length > 4 && fileName.lowercase().endsWith(".dll")) {
            fileName.substring(0, fileName.length - 4)
        } else {
            fileName
        }
}

/**
 * Derives the `ROMM_PLAYER_ALLOWED_CORES` value for an explicit [buildIdentity]:
 * `coreId=revision` pairs, semicolon-joined, sorted by coreId for determinism.
 *
 * Only cores that exist in [CoreManifest], are approved, AND support [buildIdentity] are
 * emitted. The revision is the manifest's [com.romm.androidtv.emulation.model.CoreLicenseFinding.releaseTag],
 * falling back to [com.romm.androidtv.emulation.model.CoreLicenseFinding.commitSha] when the tag
 * is blank (gambatte carries no upstream release tags). Unknown ids are dropped.
 */
fun deriveAllowedCoresForIdentity(
    installedCoreIds: Collection<String>,
    buildIdentity: String,
): String =
    installedCoreIds.distinct()
        .mapNotNull { id ->
            // The scan's `_core` strip is lossy for the synthetic test_core (libtest_core.so /
            // test_core.dll → "test"); recover it by retrying with the CMake target suffix.
            CoreManifest.findById(id) ?: CoreManifest.findById("${id}_core")
        }
        .filter { it.approved && buildIdentity in it.supportedAbis }
        .sortedBy { it.coreId }
        .joinToString(";") { core -> "${core.coreId}=${core.releaseTag.ifBlank { core.commitSha }}" }
