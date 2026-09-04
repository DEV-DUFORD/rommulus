package com.romm.desktop.storage.paths

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Path
import java.util.UUID

/**
 * The minimal `shell32` Known Folder surface RomMulus needs (plans/WINDOWS_IMPL.md §3.4).
 *
 * Method names are the exact Win32 export names and the signatures were verified against the
 * pinned JNA 5.19.1 dependency by compilation. [Guid.GUID] supplies the native GUID layout,
 * including the mixed endianness of the first three fields. This narrow
 * interface is the project-owned seam; it is deliberately tiny so it is easy to audit and to
 * fake in tests.
 */
interface Shell32KnownFolders : com.sun.jna.Library {
    /** `SHGetKnownFolderPath(rfid, dwFlags, hToken, ppwszPath)` → HRESULT (0 = S_OK). */
    fun SHGetKnownFolderPath(
        rfid: Guid.GUID,
        dwFlags: Int,
        hToken: Pointer?,
        ppwszPath: PointerByReference,
    ): Int
}

/** `ole32.CoTaskMemFree`: the only legal way to release a path returned by [Shell32KnownFolders]. */
interface Ole32CoTask : com.sun.jna.Library {
    fun CoTaskMemFree(pv: Pointer)
}

/**
 * Production [WindowsKnownFolderResolver] backed by the real Windows Known Folder API through
 * JNA (plans/WINDOWS_IMPL.md §3.4, .slim/deepwork/windows-phase-0.md Phase 1 lane 1).
 *
 * - `shell32`/`ole32` are loaded lazily on first use — constructing this class on a non-Windows
 *   host is inert; invoking it there fails explicitly with an actionable diagnostic.
 * - Resolves the per-user `FOLDERID_RoamingAppData` and `FOLDERID_LocalAppData` for the current
 *   user (`hToken = NULL`), i.e. the same locations `%APPDATA%`/`%LOCALAPPDATA%` expose, but via
 *   the documented API: it honors per-user folder redirection (registry overrides) that raw
 *   environment variables do not, and returns wide-character paths with no ANSI code-page
 *   round trip (Unicode/long-path safe, plans/WINDOWS_IMPL.md §3.4).
 * - Every failure (missing library, non-zero HRESULT, blank result) throws
 *   [IllegalStateException] carrying the numeric Win32/HRESULT value — a profile without usable
 *   known folders cannot provide the containment roots the file-security policy requires.
 */
class JnaWindowsKnownFolderResolver : WindowsKnownFolderResolver {

    private val shell32: Shell32KnownFolders by lazy {
        try {
            Native.load("shell32", Shell32KnownFolders::class.java)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "shell32 could not be loaded for Known Folder resolution — the Windows Known " +
                    "Folder API is only available on Windows hosts",
                e,
            )
        }
    }

    private val ole32: Ole32CoTask by lazy {
        try {
            Native.load("ole32", Ole32CoTask::class.java)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "ole32 could not be loaded for Known Folder result release — the Windows Known " +
                    "Folder API is only available on Windows hosts",
                e,
            )
        }
    }

    override fun roamingAppData(): Path = resolve(FOLDERID_ROAMING_APP_DATA)

    override fun localAppData(): Path = resolve(FOLDERID_LOCAL_APP_DATA)

    private fun resolve(folderId: UUID): Path {
        val out = PointerByReference()
        val hr = try {
            shell32.SHGetKnownFolderPath(Guid.GUID(folderId.toString()), KFO_FLAGS_NONE, null, out)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "SHGetKnownFolderPath($folderId) failed to invoke: ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
        if (hr != S_OK) {
            throw IllegalStateException(
                "SHGetKnownFolderPath($folderId) failed with HRESULT 0x${hr.toString(16).uppercase()}" +
                    " — the current user profile does not provide this known folder",
            )
        }
        val pointer = out.getValue()
            ?: throw IllegalStateException("SHGetKnownFolderPath($folderId) returned a null path pointer")
        try {
            val value = pointer.getWideString(0)
            if (value.isNullOrBlank()) {
                throw IllegalStateException("SHGetKnownFolderPath($folderId) returned a blank path")
            }
            return Path.of(value)
        } finally {
            ole32.CoTaskMemFree(pointer)
        }
    }

    companion object {
        /** `S_OK`: `SHGetKnownFolderPath` succeeded. */
        const val S_OK = 0

        /** No special flags: default location, current user (`hToken = NULL`). */
        private const val KFO_FLAGS_NONE = 0

        /**
         * `FOLDERID_RoamingAppData` — the per-user roaming app data root (`%APPDATA%`).
         *
         * Authoritative value from `knownfolders.h` (Windows SDK; identical in MinGW-w64
         * 14.0.0): `DEFINE_KNOWN_FOLDER(FOLDERID_RoamingAppData, 0x3eb685db, 0x65f9, 0x4cf6,
         * 0xa0, 0x3a, 0xe3, 0xef, 0x65, 0x72, 0x9f, 0x3d)`. The previously hard-coded
         * `0139D44E-6EFE-49F2-86CB-938CA492C9B4` is not a known-folder ID in any SDK header
         * (it is a corruption of `FOLDERID_CommonPrograms`, 0139D44E-6AFE-49F2-8690-3DAFCAE6FFB8),
         * so `SHGetKnownFolderPath` would fail with `E_INVALIDARG` on every call.
         */
        val FOLDERID_ROAMING_APP_DATA: UUID = UUID.fromString("3EB685DB-65F9-4CF6-A03A-E3EF65729F3D")

        /**
         * `FOLDERID_LocalAppData` — the per-user local app data root (`%LOCALAPPDATA%`).
         *
         * Authoritative value from `knownfolders.h` (Windows SDK; identical in MinGW-w64
         * 14.0.0): `DEFINE_KNOWN_FOLDER(FOLDERID_LocalAppData, 0xf1b32785, 0x6fba, 0x4fcf,
         * 0x9d, 0x55, 0x7b, 0x8e, 0x7f, 0x15, 0x70, 0x91)`. The C++ counterpart
         * (`windows_platform_paths.cpp`) carries the same GUID — keep the two in lockstep.
         */
        val FOLDERID_LOCAL_APP_DATA: UUID = UUID.fromString("F1B32785-6FBA-4FCF-9D55-7B8E7F157091")
    }
}
