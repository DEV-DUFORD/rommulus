package com.romm.desktop.platform.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Path

/**
 * The minimal `advapi32` security-descriptor surface RomMulus needs (plans/WINDOWS_IMPL.md §4.2).
 *
 * Method names are the exact Win32 export names (wide `W` variants only) and the signatures were
 * verified against the pinned JNA 5.19.1 dependency by compilation. JNA Platform ships no SDDL/
 * security-info binding, so this narrow interface is the project-owned seam; it is deliberately
 * tiny so it is easy to audit and to fake in tests.
 */
interface Advapi32Security : com.sun.jna.Library {
    fun OpenProcessToken(process: Pointer, desiredAccess: Int, token: PointerByReference): Boolean
    fun GetTokenInformation(
        token: Pointer,
        infoClass: Int,
        buffer: Pointer?,
        length: Int,
        returnLength: IntByReference,
    ): Boolean

    /** `ConvertSidToStringSidW(Sid, &StringSid)` → BOOL; caller frees the result with `LocalFree`. */
    fun ConvertSidToStringSidW(sid: Pointer, stringSid: PointerByReference): Boolean

    /**
     * `ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, revision, &sd, &len)` → BOOL.
     * The returned self-relative `SECURITY_DESCRIPTOR` is caller-owned (`LocalFree`).
     */
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
        sddl: WString,
        revision: Int,
        securityDescriptor: PointerByReference,
        length: IntByReference?,
    ): Boolean

    /**
     * `GetSecurityDescriptorDacl(pSecurityDescriptor, &daclPresent, &pDacl, &daclSize)` → BOOL
     * (securapi.h, exported by `advapi32.dll`). ABI-identical to the pinned JNA 5.19.1 Platform
     * binding `com.sun.jna.platform.win32.Advapi32.GetSecurityDescriptorDacl(SECURITY_DESCRIPTOR,
     * BOOLByReference, PACLByReference, BOOLByReference)` — a raw [IntByReference] is the same
     * 4-byte out-pointer JNA uses for the `BOOL*`/`LPDWORD*` parameters.
     *
     * Contract trap: the returned `PACL` points INTO the self-relative `SECURITY_DESCRIPTOR`
     * buffer (the OS does not allocate a copy). The caller must NOT free it and must keep the
     * descriptor alive until the PACL is no longer used.
     */
    fun GetSecurityDescriptorDacl(
        securityDescriptor: Pointer,
        daclPresent: IntByReference,
        dacl: PointerByReference,
        daclSize: IntByReference?,
    ): Boolean

    /**
     * `SetSecurityInfo` (seapi.h in the Windows SDK, aclapi.h in MinGW-w64; exported by
     * `advapi32.dll` under this exact name — there is no `W`/`A` variant because the security
     * data is passed as self-relative structures, not strings). Signature verified against both
     * the authoritative JNA 5.19.1 Platform binding `com.sun.jna.platform.win32.Advapi32.
     * SetSecurityInfo(HANDLE, int, int, Pointer, Pointer, Pointer, Pointer)` and the MinGW-w64
     * 14.0.0 header (`DWORD WINAPI SetSecurityInfo(HANDLE, SE_OBJECT_TYPE, SECURITY_INFORMATION,
     * PSID, PSID, PACL, PACL)`). Returns `SECURITY_STATUS` (32-bit): 0 = ERROR_SUCCESS, nonzero
     * is the Win32 error code.
     *
     * Contract trap: `owner`/`group` are self-relative `PSECURITY_DESCRIPTOR`s, but `dacl`/`sacl`
     * are `PACL`s (pointers INTO a descriptor's DACL/SACL) — passing the descriptor itself as the
     * DACL is a layout mismatch the OS rejects.
     */
    fun SetSecurityInfo(
        handle: Pointer,
        objectType: Int,
        securityInfo: Int,
        owner: Pointer?,
        group: Pointer?,
        dacl: Pointer?,
        sacl: Pointer?,
    ): Int
}

/** Minimal `kernel32` surface for token/SID resolution, file handles, and error formatting. */
interface Kernel32Security : com.sun.jna.Library {
    fun GetCurrentProcess(): Pointer
    fun CreateFileW(
        name: WString,
        desiredAccess: Int,
        shareMode: Int,
        securityAttributes: Pointer?,
        creationDisposition: Int,
        flagsAndAttributes: Int,
        templateFile: Pointer?,
    ): Pointer?
    fun CloseHandle(handle: Pointer): Boolean
    fun GetLastError(): Int
    fun LocalFree(pointer: Pointer)
    fun FormatMessageW(
        dwFlags: Int,
        lpSource: Pointer?,
        dwMessageId: Int,
        dwLanguageId: Int,
        lpBuffer: Pointer,
        nSize: Int,
        arguments: Pointer?,
    ): Int
}

/**
 * Production [WindowsAclApplier] backed by real NTFS ACL operations through JNA
 * (plans/WINDOWS_IMPL.md §4.2). Restricts [applyCurrentUserOnlyAcl]'s target to the current user
 * plus SYSTEM — exactly the seam contract — by replacing its DACL with:
 *
 * ```
 * D:(A;OICI;FA;;;SY)(A;OICI;FA;;;<current-user-sid>)
 * ```
 *
 * i.e. full control for LocalSystem and the owning user, inheritable to child objects (OICI),
 * and — because a DACL with only these allow ACEs implicitly denies everyone else — no other
 * account can read or write the path. The plan's mention of Administrators is intentionally not
 * granted: the reviewed seam contract is "current user + SYSTEM only", and adding an
 * `(A;OICI;FA;;;BA)` ACE later is a one-line SDDL change if audit prefers plan parity.
 *
 * Mechanics (all plain, stable Win32 APIs available to JNA 5.19.1):
 * 1. the current user's SID comes from the process token (`OpenProcessToken` +
 *    `GetTokenInformation(TokenUser)` + `ConvertSidToStringSidW`);
 * 2. the SDDL above is converted by the OS itself via
 *    `ConvertStringSecurityDescriptorToSecurityDescriptorW` — no hand-rolled descriptor layout;
 * 3. the DACL is extracted from that descriptor with `GetSecurityDescriptorDacl` — the
 *    `SetSecurityInfo` `dacl` parameter is a `PACL`, never a `PSECURITY_DESCRIPTOR` — and the
 *    PACL is applied to a handle opened with `WRITE_DAC`. The PACL points into the
 *    descriptor buffer, so the descriptor is freed only after `SetSecurityInfo` returns.
 *
 * Scope: the DACL is set on the object itself with inheritable ACEs, so objects created later
 * underneath a hardened directory inherit the restriction; pre-existing children of an
 * already-populated directory are not rewritten (fresh installs have no content yet, and
 * re-hardening each access keeps new content covered).
 *
 * Lazy Win32 loading: `advapi32`/`kernel32` load on first use — constructing this class on a
 * non-Windows host is inert; invoking it there fails explicitly. Every failure throws
 * [IllegalStateException] carrying the numeric Win32 error and its formatted message
 * (plans/WINDOWS_IMPL.md §4.4); [WindowsFileSecurityPolicy] surfaces that as a
 * [FileSecurityException] for sensitive paths (fail closed, never a success-shaped fallback).
 */
class JnaWindowsAclApplier : WindowsAclApplier {

    constructor()

    // Host-neutral test seam: when non-null these fakes replace the real natives (see the
    // internal constructor); production construction leaves them null and loads lazily.
    private var injectedAdvapi32: Advapi32Security? = null
    private var injectedKernel32: Kernel32Security? = null

    /** Host-neutral test seam: use fake Win32 libraries instead of loading the real natives. */
    internal constructor(advapi32: Advapi32Security, kernel32: Kernel32Security) {
        injectedAdvapi32 = advapi32
        injectedKernel32 = kernel32
    }

    private val advapi32: Advapi32Security by lazy {
        injectedAdvapi32 ?: try {
            Native.load("advapi32", Advapi32Security::class.java)
        } catch (e: Throwable) {
            throw unavailableError("advapi32", e)
        }
    }

    private val kernel32: Kernel32Security by lazy {
        injectedKernel32 ?: try {
            Native.load("kernel32", Kernel32Security::class.java)
        } catch (e: Throwable) {
            throw unavailableError("kernel32", e)
        }
    }

    override fun applyCurrentUserOnlyAcl(path: Path) {
        val sddl = buildCurrentUserOnlySddl(currentUserSid())
        applySddlTo(path, sddl)
    }

    /**
     * Converts [sddl] to a self-relative `SECURITY_DESCRIPTOR`, extracts its DACL with
     * `GetSecurityDescriptorDacl`, and applies that PACL — never the descriptor itself — to
     * [path] via `SetSecurityInfo`. Internal host-neutral seam: tests drive this with fake
     * libraries to pin the Win32 pointer contract without a Windows host.
     */
    internal fun applySddlTo(path: Path, sddl: String) {
        val sdPointer = convertSddl(sddl, path)
        try {
            // SetSecurityInfo's `dacl` parameter is a PACL, not a PSECURITY_DESCRIPTOR: extract
            // the DACL from the self-relative descriptor first. The returned PACL points INTO
            // the descriptor buffer (no separate allocation), so it must not be freed and the
            // descriptor must outlive the SetSecurityInfo call below.
            val pAcl = extractDacl(sdPointer, path)
            val handle = openForSecurityChanges(path)
            try {
                val error = advapi32.SetSecurityInfo(
                    handle,
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION,
                    null,
                    null,
                    pAcl,
                    null,
                )
                if (error != ERROR_SUCCESS) {
                    throw IllegalStateException(
                        "SetSecurityInfo failed for $path: ${describeWin32Error(error)}",
                    )
                }
            } finally {
                kernel32.CloseHandle(handle)
            }
        } finally {
            kernel32.LocalFree(sdPointer)
        }
    }

    /**
     * Extracts the DACL from the self-relative descriptor [sd] via `GetSecurityDescriptorDacl`.
     * The returned PACL points INTO the descriptor buffer (the OS does not allocate a copy), so
     * the caller must keep [sd] alive until the PACL is no longer used and must NOT free the
     * PACL itself.
     */
    private fun extractDacl(sd: Pointer, path: Path): Pointer {
        val daclPresent = IntByReference(0)
        val daclRef = PointerByReference()
        if (!advapi32.GetSecurityDescriptorDacl(sd, daclPresent, daclRef, null)) {
            throw IllegalStateException(
                "GetSecurityDescriptorDacl failed for $path: ${describeLastError()}",
            )
        }
        if (daclPresent.getValue() == 0) {
            throw IllegalStateException(
                "the converted descriptor for $path carries no DACL; refusing to apply it",
            )
        }
        return daclRef.getValue()
            ?: throw IllegalStateException("GetSecurityDescriptorDacl returned a null DACL for $path")
    }

    /** The current user's SID as a string (e.g. `S-1-5-21-...-1001`), from the process token. */
    internal fun currentUserSid(): String {
        val tokenRef = PointerByReference()
        if (!advapi32.OpenProcessToken(kernel32.GetCurrentProcess(), TOKEN_QUERY, tokenRef)) {
            throw IllegalStateException("OpenProcessToken failed: ${describeLastError()}")
        }
        val token = tokenRef.getValue()
            ?: throw IllegalStateException("OpenProcessToken returned a null token handle")
        try {
            // First call sizes the buffer; second fills it. The sizing call is specified to
            // return FALSE with GetLastError() == ERROR_INSUFFICIENT_BUFFER (122) while filling
            // ReturnLength — that failure is the SUCCESS path; anything else is a real error.
            val lengthRef = IntByReference(0)
            if (!advapi32.GetTokenInformation(token, TOKEN_INFORMATION_CLASS_USER, null, 0, lengthRef)) {
                val code = kernel32.GetLastError()
                if (code != ERROR_INSUFFICIENT_BUFFER) {
                    throw IllegalStateException("GetTokenInformation (size query) failed: ${describeWin32Error(code)}")
                }
            }
            val size = lengthRef.getValue()
            if (size <= 0) {
                throw IllegalStateException("GetTokenInformation reported an invalid TOKEN_USER size $size")
            }
            val buffer = Memory(size.toLong())
            try {
                if (!advapi32.GetTokenInformation(token, TOKEN_INFORMATION_CLASS_USER, buffer, size, lengthRef)) {
                    throw IllegalStateException("GetTokenInformation (read) failed: ${describeLastError()}")
                }
                // TOKEN_USER is a single PSID field at offset 0.
                val userSid = buffer.getPointer(0)
                    ?: throw IllegalStateException("TOKEN_USER carries a null UserSid")
                val sidStringRef = PointerByReference()
                if (!advapi32.ConvertSidToStringSidW(userSid, sidStringRef)) {
                    throw IllegalStateException("ConvertSidToStringSidW failed: ${describeLastError()}")
                }
                val sidStringPointer = sidStringRef.getValue()
                    ?: throw IllegalStateException("ConvertSidToStringSidW returned a null SID string")
                try {
                    // ConvertSidToStringSidW returns an LPWSTR — read it as a wide string (an
                    // ANSI read would corrupt the SIDs into garbage and the SDDL would be
                    // rejected by ConvertStringSecurityDescriptorToSecurityDescriptorW).
                    return sidStringPointer.getWideString(0)
                } finally {
                    kernel32.LocalFree(sidStringPointer)
                }
            } finally {
                buffer.clear()
            }
        } finally {
            kernel32.CloseHandle(token)
        }
    }

    private fun convertSddl(sddl: String, path: Path): Pointer {
        val sdRef = PointerByReference()
        if (!advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString(sddl),
                SDDL_REVISION_1,
                sdRef,
                null,
            )
        ) {
            throw IllegalStateException(
                "ConvertStringSecurityDescriptorToSecurityDescriptorW rejected the ACL descriptor " +
                    "for $path: ${describeLastError()}",
            )
        }
        return sdRef.getValue()
            ?: throw IllegalStateException("ConvertStringSecurityDescriptorToSecurityDescriptorW returned a null descriptor")
    }

    private fun openForSecurityChanges(path: Path): Pointer {
        // `WRITE_DAC` is the access right that authorizes a DACL change via `SetSecurityInfo`.
        // It is held by the object's owner (the current user, who created the path) without any
        // elevated privilege. `ACCESS_SYSTEM_SECURITY` (0x01000000) must NOT be requested: it
        // requires `SeSecurityPrivilege`, which a normal (non-admin) user does not hold, so
        // `CreateFileW` would fail with `ERROR_ACCESS_DENIED` and hardening would fail closed.
        //
        // `FILE_FLAG_BACKUP_SEMANTICS` (0x02000000, winbase.h) is REQUIRED for `CreateFileW`
        // to open a DIRECTORY at all — without it the call fails with `ERROR_ACCESS_DENIED`
        // for every directory target, so hardening the state/data roots (the primary use of
        // this applier) would fail closed. The flag is a no-op for regular files, so one
        // open path serves both.
        val handle = kernel32.CreateFileW(
            WString(path.toAbsolutePath().toString()),
            WRITE_DAC,
            FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE,
            null,
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL or FILE_FLAG_BACKUP_SEMANTICS,
            null,
        )
        // JNA 5.19.1 exposes the raw address through the static Pointer.nativeValue.
        if (handle == null || Pointer.nativeValue(handle) == INVALID_HANDLE_VALUE) {
            throw IllegalStateException(
                "CreateFileW failed for $path (the path must exist and be openable for security " +
                    "changes): ${describeLastError()}",
            )
        }
        return handle
    }

    /** `Win32 error <code>: <formatted message>` — the message is best-effort. */
    private fun describeLastError(): String = describeWin32Error(kernel32.GetLastError())

    private fun describeWin32Error(code: Int): String {
        val buffer = Memory(ERROR_MESSAGE_BUFFER_CHARS * 2L)
        val written = try {
            kernel32.FormatMessageW(
                FORMAT_MESSAGE_FROM_SYSTEM or FORMAT_MESSAGE_IGNORE_INSERTS,
                null,
                code,
                0,
                buffer,
                ERROR_MESSAGE_BUFFER_CHARS,
                null,
            )
        } catch (_: Throwable) {
            0
        }
        return if (written > 0) {
            "Win32 error $code: ${buffer.getWideString(0).trim()}"
        } else {
            "Win32 error $code"
        }
    }

    private fun unavailableError(library: String, cause: Throwable): IllegalStateException =
        IllegalStateException(
            "$library could not be loaded for NTFS ACL hardening — the Windows security APIs " +
                "are only available on Windows hosts",
            cause,
        )

    companion object {
        // winnt.h / seapi.h
        const val SE_FILE_OBJECT = 1
        const val DACL_SECURITY_INFORMATION = 4
        const val TOKEN_QUERY = 0x0008
        const val TOKEN_INFORMATION_CLASS_USER = 1
        // winnt.h file access rights: `WRITE_DAC` (0x00040000) authorizes a DACL change.
        const val WRITE_DAC = 0x00040000
        const val SDDL_REVISION_1 = 1
        const val ERROR_SUCCESS = 0
        // winerror.h
        const val ERROR_INSUFFICIENT_BUFFER = 122
        // winbase.h / fileapi.h
        private const val FILE_SHARE_READ = 0x1
        private const val FILE_SHARE_WRITE = 0x2
        private const val FILE_SHARE_DELETE = 0x4
        private const val OPEN_EXISTING = 3
        private const val FILE_ATTRIBUTE_NORMAL = 0x80
        // winbase.h: required to open directories with CreateFileW (no-op for files).
        private const val FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
        private const val INVALID_HANDLE_VALUE = -1L
        // winbase.h (FormatMessageW)
        private const val FORMAT_MESSAGE_FROM_SYSTEM = 0x00001000
        private const val FORMAT_MESSAGE_IGNORE_INSERTS = 0x00000200
        private const val ERROR_MESSAGE_BUFFER_CHARS = 256

        /**
         * The exact DACL SDDL this applier applies: full control for LocalSystem (`SY`) and the
         * [userSid], both inheritable to child objects (`OICI`); everything else is denied by the
         * absence of any other allow ACE. Exposed for host-neutral testing of the descriptor text.
         */
        internal fun buildCurrentUserOnlySddl(userSid: String): String =
            "D:(A;OICI;FA;;;SY)(A;OICI;FA;;;$userSid)"
    }
}
