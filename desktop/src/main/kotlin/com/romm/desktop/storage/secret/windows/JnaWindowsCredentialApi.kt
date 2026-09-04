package com.romm.desktop.storage.secret.windows

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * The minimal `advapi32` Credential Manager surface RomMulus needs (plans/WINDOWS_IMPL.md §4.3).
 *
 * Method names are the exact Win32 export names (wide `W` variants only — no ANSI code-page
 * round-trips, plans/WINDOWS_IMPL.md §3.4) and the signatures were verified against the pinned
 * JNA 5.19.1 dependency by compilation. JNA Platform ships no Credential Manager binding, so this
 * narrow interface is the project-owned seam; it is deliberately tiny so it is easy to audit and
 * to fake in tests.
 */
interface WindowsCredentialLibrary : com.sun.jna.Library {
    fun CredWriteW(credential: Credential, flags: Int): Boolean
    fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
    fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
    fun CredEnumerateW(filter: WString, flags: Int, count: IntByReference, list: PointerByReference): Boolean
    fun CredFree(credential: Pointer)
}

/** Minimal `kernel32` surface for Win32 error formatting (numeric code + message). */
interface Win32Kernel32 : com.sun.jna.Library {
    fun GetLastError(): Int
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
 * The Win32 `CREDENTIALW` structure (Windows SDK `wincred.h`) as a JNA [Structure] — the
 * exact 12-field generic-credential layout, in declaration order:
 *
 * ```c
 * typedef struct _CREDENTIALW {
 *     DWORD                Flags;
 *     DWORD                Type;
 *     LPWSTR               TargetName;
 *     LPWSTR               Comment;
 *     FILETIME             LastWritten;
 *     DWORD                CredentialBlobSize;
 *     LPBYTE               CredentialBlob;
 *     DWORD                Persist;
 *     DWORD                AttributeCount;
 *     CREDENTIAL_ATTRIBUTE *Attributes;
 *     LPWSTR               TargetAlias;
 *     LPWSTR               UserName;
 * } CREDENTIALW;
 * ```
 *
 * Authoritative x86_64 offsets: flags@0, type@4, targetName@8, comment@16, lastWritten@24,
 * credentialBlobSize@32, credentialBlob@40, persist@48, attributeCount@52, attributes@56,
 * targetAlias@64, userName@72; total size 80 bytes. (Identical on any LP64 host, e.g. aarch64;
 * pinned by `JnaWindowsCredentialApiTest`.)
 *
 * JNA-specific layout traps handled here:
 *  - Every field is `@JvmField`: JNA 5.19.1's layout engine collects only **public instance
 *    fields** (no Kotlin-property support); a plain `var` compiles to a private backing field
 *    and JNA would see zero fields and refuse to initialize the struct.
 *  - `credentialBlob` is the `LPBYTE` **pointer** field. Declaring it `ByteArray` would make JNA
 *    treat it as an inline variable-length field (blob bytes written at the pointer's offset,
 *    shifting every later field), corrupting the struct; a [Pointer] keeps the SDK layout.
 *  - `attributes` is the `CREDENTIAL_ATTRIBUTE*` **pointer** field ([Pointer] = 8 bytes on 64-bit);
 *    RomMulus never uses per-credential attributes, so it stays null with `attributeCount = 0`.
 *  - `lastWritten` is the by-value 8-byte `FILETIME` (two DWORDs); JNA NPEs when a by-value
 *    embedded [Structure] field is null at read/write time, so it is default-constructed
 *    (zero-initialized) and must stay non-null.
 */
class Credential() : Structure() {

    /**
     * Reads an existing native `CREDENTIALW` (from `CredReadW` / `CredEnumerateW`). Kotlin
     * secondary constructors must delegate to the primary, so the native memory is attached via
     * JNA's `useMemory` after the no-arg primary runs.
     */
    constructor(pointer: Pointer) : this() {
        useMemory(pointer)
    }

    // JNA 5.19.1's Structure layout engine collects only PUBLIC instance fields (it has no
    // Kotlin-property support), so every field is @JvmField — a plain Kotlin `var` would compile
    // to a private backing field and JNA would see zero fields.
    @JvmField var flags: Int = 0
    @JvmField var type: Int = 0
    @JvmField var targetName: WString? = null
    @JvmField var comment: WString? = null
    @JvmField var lastWritten: WinBase.FILETIME = WinBase.FILETIME()
    @JvmField var credentialBlobSize: Int = 0
    @JvmField var credentialBlob: Pointer? = null
    @JvmField var persist: Int = 0
    @JvmField var attributeCount: Int = 0
    @JvmField var attributes: Pointer? = null
    @JvmField var targetAlias: WString? = null
    @JvmField var userName: WString? = null

    override fun getFieldOrder(): List<String> = listOf(
        "flags", "type", "targetName", "comment", "lastWritten", "credentialBlobSize",
        "credentialBlob", "persist", "attributeCount", "attributes", "targetAlias", "userName",
    )
}

/**
 * Production [WindowsCredentialApi] backed by the real Win32 Credential Manager through JNA.
 *
 * Lazy Win32 loading: `advapi32` (and `kernel32` for error formatting) are loaded on first use,
 * never at class-load time — so merely constructing this class on a non-Windows host is inert,
 * and every operation on such a host degrades to `Unavailable` instead of throwing.
 *
 * No method throws; every failure is mapped into the sealed results with a reason carrying the
 * numeric Win32 error and its formatted message. Secret bytes never appear in any reason.
 */
class JnaWindowsCredentialApi : WindowsCredentialApi {

    private val advapi32: WindowsCredentialLibrary? by lazy {
        runCatching { Native.load("advapi32", WindowsCredentialLibrary::class.java) }.getOrNull()
    }

    private val kernel32: Win32Kernel32? by lazy {
        runCatching { Native.load("kernel32", Win32Kernel32::class.java) }.getOrNull()
    }

    override fun write(targetName: String, secret: ByteArray): CredentialWriteResult {
        val lib = advapi32 ?: return CredentialWriteResult.Unavailable(unavailableReason())
        // The blob lives in its own native allocation: the struct field is the LPBYTE pointer to
        // it (see [Credential]). The allocation must outlive the native call, then the secret
        // bytes are zeroed and the memory freed (byte hygiene).
        val blobMemory = if (secret.isEmpty()) {
            null
        } else {
            Memory(secret.size.toLong()).also { it.write(0, secret, 0, secret.size) }
        }
        val credential = Credential().apply {
            type = CRED_TYPE_GENERIC
            // Explicit `this.`: the function parameter `targetName` shadows the struct field.
            this.targetName = WString(targetName)
            persist = CRED_PERSIST_LOCAL_MACHINE
            credentialBlob = blobMemory
            credentialBlobSize = secret.size
        }
        return try {
            if (lib.CredWriteW(credential, 0)) {
                CredentialWriteResult.Ok
            } else {
                classifyWriteFailure()
            }
        } catch (e: Throwable) {
            CredentialWriteResult.Unavailable("CredWriteW failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            blobMemory?.let {
                try {
                    it.clear(it.size()) // zero the whole allocation (JNA 5.x: clear(size) from offset 0)
                } catch (_: Throwable) {
                    // best-effort byte cleanup
                }
                it.close()
            }
        }
    }

    override fun read(targetName: String): CredentialReadResult {
        val lib = advapi32 ?: return CredentialReadResult.Unavailable(unavailableReason())
        val out = PointerByReference()
        val ok = try {
            lib.CredReadW(WString(targetName), CRED_TYPE_GENERIC, 0, out)
        } catch (e: Throwable) {
            return CredentialReadResult.Unavailable("CredReadW failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!ok) {
            // Capture the error code exactly once: GetLastError is per-thread and must be read
            // before any other Win32 call (including FormatMessageW inside describeError).
            val code = lastError()
            return when (code) {
                ERROR_NOT_FOUND, ERROR_CREDENTIAL_UNKNOWN -> CredentialReadResult.NotFound
                ERROR_ACCESS_DENIED -> CredentialReadResult.Denied(describeError(code))
                else -> CredentialReadResult.Unavailable(describeError(code))
            }
        }
        val pointer = out.getValue()
            ?: return CredentialReadResult.Unavailable("CredReadW returned a null credential pointer")
        return try {
            val credential = Credential(pointer)
            credential.read()
            // The blob is a separate OS allocation referenced by the struct's LPBYTE field;
            // copy it out (the backend owns the copy and zeroes it after use).
            val blobPointer = credential.credentialBlob
            val blobSize = credential.credentialBlobSize
            // Defend against a corrupt store: a generic credential blob is at most
            // CRED_MAX_CREDENTIAL_BLOB_SIZE bytes; anything else is not ours to trust.
            val blob = if (blobPointer != null && blobSize in 1..CRED_MAX_CREDENTIAL_BLOB_SIZE) {
                blobPointer.getByteArray(0, blobSize)
            } else {
                ByteArray(0)
            }
            CredentialReadResult.Found(blob)
        } catch (e: Throwable) {
            CredentialReadResult.Unavailable("credential blob read failed: ${e.javaClass.simpleName}")
        } finally {
            // CredFree releases the OS allocation for the CREDENTIAL structure AND its blob.
            lib.CredFree(pointer)
        }
    }

    override fun delete(targetName: String): CredentialDeleteResult {
        val lib = advapi32 ?: return CredentialDeleteResult.Unavailable(unavailableReason())
        val ok = try {
            lib.CredDeleteW(WString(targetName), CRED_TYPE_GENERIC, 0)
        } catch (e: Throwable) {
            return CredentialDeleteResult.Unavailable("CredDeleteW failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (ok) return CredentialDeleteResult.Ok
        val code = lastError()
        return when (code) {
            // Idempotent logout: deleting an already-absent credential is success.
            ERROR_NOT_FOUND, ERROR_CREDENTIAL_UNKNOWN -> CredentialDeleteResult.Ok
            ERROR_ACCESS_DENIED -> CredentialDeleteResult.Denied(describeError(code))
            else -> CredentialDeleteResult.Unavailable(describeError(code))
        }
    }

    override fun enumerateTargets(filter: String): CredentialEnumerateResult {
        val lib = advapi32 ?: return CredentialEnumerateResult.Unavailable(unavailableReason())
        val count = IntByReference(0)
        val list = PointerByReference()
        val ok = try {
            lib.CredEnumerateW(WString(filter), 0, count, list)
        } catch (e: Throwable) {
            return CredentialEnumerateResult.Unavailable("CredEnumerateW failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (!ok) {
            val code = lastError()
            return when (code) {
                ERROR_ACCESS_DENIED -> CredentialEnumerateResult.Denied(describeError(code))
                else -> CredentialEnumerateResult.Unavailable(describeError(code))
            }
        }
        val listPointer = list.getValue() ?: return CredentialEnumerateResult.Ok(emptyList())
        return try {
            val names = ArrayList<String>(count.getValue().coerceAtLeast(0))
            for (i in 0 until count.getValue()) {
                val credentialPointer = Pointer(listPointer.getLong(i.toLong() * Native.POINTER_SIZE))
                val credential = Credential(credentialPointer)
                credential.read()
                credential.targetName?.let { names.add(it.toString()) }
                // Do NOT CredFree the per-element pointers: for an array returned by
                // CredEnumerateW, a single CredFree on the aggregate buffer releases the array
                // AND every CREDENTIAL structure (and blob) it contains (MSDN CredFree: "The
                // memory used by the individual CREDENTIAL structures in the array is freed
                // automatically"). Freeing the elements too would be a double free.
            }
            CredentialEnumerateResult.Ok(names)
        } catch (e: Throwable) {
            CredentialEnumerateResult.Unavailable("credential enumeration read failed: ${e.javaClass.simpleName}")
        } finally {
            // The ONE CredFree for the whole enumeration: the returned aggregate buffer.
            lib.CredFree(listPointer)
        }
    }

    private fun lastError(): Int = kernel32?.GetLastError() ?: 0

    /** Classifies a failed write: access-denied is distinct from everything else (fail closed). */
    private fun classifyWriteFailure(): CredentialWriteResult {
        val code = lastError()
        return when (code) {
            ERROR_ACCESS_DENIED -> CredentialWriteResult.Denied(describeError(code))
            else -> CredentialWriteResult.Unavailable(describeError(code).ifEmpty { "CredWriteW failed" })
        }
    }

    /** "Win32 error <code>: <formatted message>" — the message is best-effort. */
    private fun describeError(code: Int): String {
        val k32 = kernel32 ?: return "Win32 error $code"
        return try {
            val buffer = Memory(ERROR_MESSAGE_BUFFER_CHARS * 2L)
            val written = k32.FormatMessageW(
                FORMAT_MESSAGE_FROM_SYSTEM or FORMAT_MESSAGE_IGNORE_INSERTS,
                null,
                code,
                0,
                buffer,
                ERROR_MESSAGE_BUFFER_CHARS,
                null,
            )
            if (written > 0) {
                "Win32 error $code: ${buffer.getWideString(0).trim()}"
            } else {
                "Win32 error $code"
            }
        } catch (_: Throwable) {
            "Win32 error $code"
        }
    }

    private fun unavailableReason(): String =
        "advapi32 could not be loaded — the Windows Credential Manager is only available on " +
            "Windows hosts"

    internal companion object {
        // wincred.h — authoritative Windows SDK values.
        // #define CRED_TYPE_GENERIC 1
        // #define CRED_PERSIST_LOCAL_MACHINE 2  (CRED_PERSIST_SESSION is 1)
        // #define CRED_MAX_CREDENTIAL_BLOB_SIZE 2560
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val CRED_MAX_CREDENTIAL_BLOB_SIZE = 2560
        const val ERROR_ACCESS_DENIED = 5
        const val ERROR_NOT_FOUND = 1168
        const val ERROR_CREDENTIAL_UNKNOWN = 1212
        // winbase.h
        const val FORMAT_MESSAGE_FROM_SYSTEM = 0x00001000
        const val FORMAT_MESSAGE_IGNORE_INSERTS = 0x00000200
        const val ERROR_MESSAGE_BUFFER_CHARS = 256
    }
}
