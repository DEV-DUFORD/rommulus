package com.romm.desktop.platform.security

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JnaWindowsAclApplierTest {

    @TempDir
    lateinit var tempDir: Path

    /** Pins the exact DACL descriptor text (host-neutral). */
    @Test
    fun `sddl grants inheritable full control to system and the user only`() {
        val sddl = JnaWindowsAclApplier.buildCurrentUserOnlySddl("S-1-5-21-1111-2222-3333-1001")

        assertThat(sddl).isEqualTo("D:(A;OICI;FA;;;SY)(A;OICI;FA;;;S-1-5-21-1111-2222-3333-1001)")
        // OICI on both ACEs: children of a hardened directory inherit the restriction.
        assertThat(sddl).contains("OICI")
    }

    /**
     * Live token/SID resolution on windows-2022: the SID must come back as a well-formed
     * wide string (regression for the ANSI-read bug in `ConvertSidToStringSidW` handling).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `resolves the current user sid as a well-formed wide string`() {
        val sid = JnaWindowsAclApplier().currentUserSid()

        assertThat(sid).matches("S-1-5-\\d+(?:-\\d+)+")
    }

    /** Real NTFS ACL round-trip — the host-native confirmation on windows-2022. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `applies the user-only acl to a real directory and keeps it usable`() {
        val dir = Files.createDirectories(tempDir.resolve("acl-target"))

        JnaWindowsAclApplier().applyCurrentUserOnlyAcl(dir)
        // Idempotent: WindowsFileSecurityPolicy re-hardens on every access.
        JnaWindowsAclApplier().applyCurrentUserOnlyAcl(dir)

        // The owning user keeps full control: children can still be created and written.
        val child = dir.resolve("child.txt")
        Files.writeString(child, "still-writable")
        assertThat(Files.readString(child)).isEqualTo("still-writable")
    }

    @Test
    fun `set file security receives the converted descriptor`() {
        val advapi32 = FakeAdvapi32(Memory(64))
        val kernel32 = FakeKernel32()

        JnaWindowsAclApplier(advapi32, kernel32)
            .applySddlTo(tempDir, "D:(A;OICI;FA;;;SY)")

        assertThat(advapi32.appliedDescriptor).isEqualTo(advapi32.sd)
        assertThat(advapi32.appliedSecurityInfo)
            .isEqualTo(JnaWindowsAclApplier.DACL_SECURITY_INFORMATION)
        assertThat(kernel32.localFreed).containsExactly(advapi32.sd)
    }

    @Test
    fun `set file security failure includes the win32 error`() {
        val advapi32 = FakeAdvapi32(Memory(64), setSecuritySucceeds = false)
        val kernel32 = FakeKernel32(lastError = 1300)

        assertThatThrownBy {
            JnaWindowsAclApplier(advapi32, kernel32)
                .applySddlTo(tempDir, "D:(A;OICI;FA;;;SY)")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("SetFileSecurityW failed")
            .hasMessageContaining("Win32 error 1300")
        assertThat(kernel32.localFreed).containsExactly(advapi32.sd)
    }

    private class FakeAdvapi32(
        val sd: Pointer,
        private val setSecuritySucceeds: Boolean = true,
    ) : Advapi32Security {

        var appliedDescriptor: Pointer? = null
        var appliedSecurityInfo: Int? = null

        override fun OpenProcessToken(
            process: Pointer,
            desiredAccess: Int,
            token: PointerByReference,
        ): Boolean = error("not used by applySddlTo")

        override fun GetTokenInformation(
            token: Pointer,
            infoClass: Int,
            buffer: Pointer?,
            length: Int,
            returnLength: IntByReference,
        ): Boolean = error("not used by applySddlTo")

        override fun ConvertSidToStringSidW(sid: Pointer, stringSid: PointerByReference): Boolean =
            error("not used by applySddlTo")

        override fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl: WString,
            revision: Int,
            securityDescriptor: PointerByReference,
            length: IntByReference?,
        ): Boolean {
            securityDescriptor.setValue(sd)
            return true
        }

        override fun SetFileSecurityW(
            fileName: WString,
            securityInfo: Int,
            securityDescriptor: Pointer,
        ): Boolean {
            appliedDescriptor = securityDescriptor
            appliedSecurityInfo = securityInfo
            return setSecuritySucceeds
        }
    }

    /** Inert kernel32: a valid fake handle, recorded frees, canned error code. */
    private class FakeKernel32(private val lastError: Int = 0) : Kernel32Security {

        val localFreed = mutableListOf<Pointer>()
        var createFileDesiredAccess: Int? = null
        var createFileFlags: Int? = null

        override fun GetCurrentProcess(): Pointer = Memory(1)

        override fun CreateFileW(
            name: WString,
            desiredAccess: Int,
            shareMode: Int,
            securityAttributes: Pointer?,
            creationDisposition: Int,
            flagsAndAttributes: Int,
            templateFile: Pointer?,
        ): Pointer {
            createFileDesiredAccess = desiredAccess
            createFileFlags = flagsAndAttributes
            return Memory(1)
        }

        override fun CloseHandle(handle: Pointer): Boolean = true

        override fun GetLastError(): Int = lastError

        override fun LocalFree(pointer: Pointer) {
            localFreed.add(pointer)
        }

        override fun FormatMessageW(
            dwFlags: Int,
            lpSource: Pointer?,
            dwMessageId: Int,
            dwLanguageId: Int,
            lpBuffer: Pointer,
            nSize: Int,
            arguments: Pointer?,
        ): Int = 0
    }
}
