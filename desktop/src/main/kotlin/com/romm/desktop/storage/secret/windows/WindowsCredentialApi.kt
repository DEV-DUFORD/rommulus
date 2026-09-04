package com.romm.desktop.storage.secret.windows

/**
 * Narrow, fakeable seam over the Windows Credential Manager generic-credential APIs
 * (plans/WINDOWS_IMPL.md §4.3: "Use a narrow, maintained Win32 binding ... rather than shelling
 * out to PowerShell or `cmdkey`").
 *
 * The production implementation is [JnaWindowsCredentialApi], which loads `advapi32` lazily
 * through JNA only when an operation actually runs. Unit tests inject an in-memory fake so the
 * backend's policy logic (target derivation, blob framing, outcome mapping, redaction) is fully
 * exercisable on macOS/Linux; the real binding is exercised on `windows-2022` by the gated
 * integration test.
 *
 * Contract — no method may throw. Every failure is translated into one of the sealed results
 * below so [WindowsCredentialBackend] can fail closed without an exception boundary. Reasons are
 * diagnostic strings (Win32 error code + formatted message where available) and MUST NOT contain
 * secret bytes or credential blob contents.
 */
interface WindowsCredentialApi {

    /**
     * Writes (or replaces) the generic credential [targetName] with [secret] as its credential
     * blob. [secret] is the app-level framed payload, NOT the raw token — framing is the
     * backend's responsibility so the API stays storage-agnostic.
     *
     * Replacement semantics: an existing credential with the same target name and generic type is
     * overwritten atomically from the caller's perspective (Win32 `CredWriteW` contract).
     */
    fun write(targetName: String, secret: ByteArray): CredentialWriteResult

    /** Reads the credential blob for [targetName], or [CredentialReadResult.NotFound] if absent. */
    fun read(targetName: String): CredentialReadResult

    /**
     * Deletes the exact credential [targetName]. Deleting an already-absent credential is
     * reported as [CredentialDeleteResult.Ok] (idempotent logout).
     */
    fun delete(targetName: String): CredentialDeleteResult

    /**
     * Lists target names whose names match [filter] (Win32 `CredEnumerateW` wildcard filter).
     * Used by the backend to probe availability and to scope [WindowsCredentialBackend.deleteAll]
     * to this application's credentials only.
     */
    fun enumerateTargets(filter: String): CredentialEnumerateResult
}

/** Outcome of a credential write. */
sealed interface CredentialWriteResult {
    data object Ok : CredentialWriteResult

    /** The Credential Manager could not be reached (library not loaded, service error, ...). */
    data class Unavailable(val reason: String) : CredentialWriteResult

    /** The OS refused the write (e.g. Win32 ERROR_ACCESS_DENIED). */
    data class Denied(val reason: String) : CredentialWriteResult
}

/** Outcome of a credential read. */
sealed interface CredentialReadResult {
    /** The credential exists; [bytes] is its raw credential blob (caller must clean up). */
    data class Found(val bytes: ByteArray) : CredentialReadResult

    /** No credential exists under the target name (Win32 ERROR_NOT_FOUND / CREDENTIAL_UNKNOWN). */
    data object NotFound : CredentialReadResult

    /** The Credential Manager could not be reached. */
    data class Unavailable(val reason: String) : CredentialReadResult

    /** The OS refused the read (e.g. Win32 ERROR_ACCESS_DENIED). */
    data class Denied(val reason: String) : CredentialReadResult
}

/** Outcome of a credential delete. */
sealed interface CredentialDeleteResult {
    /** Deleted, or already absent (idempotent). */
    data object Ok : CredentialDeleteResult

    /** The Credential Manager could not be reached. */
    data class Unavailable(val reason: String) : CredentialDeleteResult

    /** The OS refused the delete. */
    data class Denied(val reason: String) : CredentialDeleteResult
}

/** Outcome of a credential enumeration. */
sealed interface CredentialEnumerateResult {
    data class Ok(val targetNames: List<String>) : CredentialEnumerateResult

    data class Unavailable(val reason: String) : CredentialEnumerateResult

    data class Denied(val reason: String) : CredentialEnumerateResult
}
