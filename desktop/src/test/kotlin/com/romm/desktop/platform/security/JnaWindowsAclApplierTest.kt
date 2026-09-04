package com.romm.desktop.platform.security

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.BOOL
import com.sun.jna.platform.win32.WinDef.BOOLByReference
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

    /**
     * Host-neutral pin of the Win32 pointer contract (regression for passing the
     * `SECURITY_DESCRIPTOR` where a `PACL` is required): `SetSecurityInfo` must receive the PACL
     * extracted by `GetSecurityDescriptorDacl` — never the descriptor itself — and the descriptor
     * (not the PACL) is the only pointer handed to `LocalFree`, exactly once.
     */
    @Test
    fun `set security info receives the extracted pacl not the security descriptor`() {
        val sd = Memory(64)
        val pAcl = Memory(8)
        val advapi32 = FakeAdvapi32(sd, pAcl)
        val kernel32 = FakeKernel32()
        val applier = JnaWindowsAclApplier(advapi32, kernel32)

        applier.applySddlTo(tempDir, "D:(A;OICI;FA;;;SY)")

        assertThat(advapi32.setSecurityInfoDacl).isEqualTo(pAcl)
        assertThat(advapi32.setSecurityInfoDacl).isNotEqualTo(sd)
        assertThat(advapi32.setSecurityInfoObjectType).isEqualTo(JnaWindowsAclApplier.SE_FILE_OBJECT)
        assertThat(advapi32.setSecurityInfoInfo).isEqualTo(JnaWindowsAclApplier.DACL_SECURITY_INFORMATION)
        // The PACL points into the descriptor: only the descriptor is freed, exactly once.
        assertThat(kernel32.localFreed).containsExactly(sd)
    }

    /** Fail closed when the converted descriptor carries no DACL; the descriptor is still freed. */
    @Test
    fun `refuses to apply when the descriptor carries no dacl`() {
        val advapi32 = FakeAdvapi32(Memory(64), Memory(8), daclPresentValue = 0)
        val kernel32 = FakeKernel32()
        val applier = JnaWindowsAclApplier(advapi32, kernel32)

        assertThatThrownBy { applier.applySddlTo(tempDir, "D:(A;OICI;FA;;;SY)") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("carries no DACL")
        assertThat(advapi32.setSecurityInfoDacl).isNull()
        assertThat(kernel32.localFreed).containsExactly(advapi32.sd)
    }

    /** `GetSecurityDescriptorDacl` failure throws with the Win32 error; nothing is applied. */
    @Test
    fun `get security descriptor dacl failure throws with the win32 error`() {
        val advapi32 = FakeAdvapi32(Memory(64), Memory(8), getDaclSucceeds = false)
        val kernel32 = FakeKernel32(lastError = 1300)
        val applier = JnaWindowsAclApplier(advapi32, kernel32)

        assertThatThrownBy { applier.applySddlTo(tempDir, "D:(A;OICI;FA;;;SY)") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("GetSecurityDescriptorDacl failed")
            .hasMessageContaining("Win32 error 1300")
        assertThat(advapi32.setSecurityInfoDacl).isNull()
        assertThat(kernel32.localFreed).containsExactly(advapi32.sd)
    }

    /**
     * Host-neutral pin (regression for the `ACCESS_SYSTEM_SECURITY`/`SeSecurityPrivilege`
     * dependency): the handle for a DACL change must be opened with `WRITE_DAC` — the right the
     * current user (the object's owner) holds without elevation — and must NOT request
     * `ACCESS_SYSTEM_SECURITY` (0x01000000), which requires `SeSecurityPrivilege`, a privilege a
     * normal (non-admin) user does not hold. Requesting it makes `CreateFileW` fail with
     * `ERROR_ACCESS_DENIED`, so hardening would fail closed for every non-admin user.
     */
    @Test
    fun `opens the security handle with write dac and not access system security`() {
        val advapi32 = FakeAdvapi32(Memory(64), Memory(8))
        val kernel32 = FakeKernel32()
        val applier = JnaWindowsAclApplier(advapi32, kernel32)

        applier.applySddlTo(tempDir, "D:(A;OICI;FA;;;SY)")

        val desiredAccess = kernel32.createFileDesiredAccess
            ?: throw AssertionError("CreateFileW was not called")
        assertThat(desiredAccess and JnaWindowsAclApplier.WRITE_DAC)
            .isEqualTo(JnaWindowsAclApplier.WRITE_DAC)
        // ACCESS_SYSTEM_SECURITY (0x01000000) must not be requested: it needs SeSecurityPrivilege.
        assertThat(desiredAccess and 0x01000000).isEqualTo(0)
    }

    /**
     * Host-neutral pin (regression for the directory-handle bug): the security handle must be
     * opened with `FILE_FLAG_BACKUP_SEMANTICS` (0x02000000) — without it `CreateFileW` fails
     * with `ERROR_ACCESS_DENIED` for every DIRECTORY target, so hardening the state/data roots
     * would fail closed. The flag is a no-op for regular files, so files keep working too.
     */
    @Test
    fun `opens the security handle with backup semantics so directories open`() {
        val advapi32 = FakeAdvapi32(Memory(64), Memory(8))
        val kernel32 = FakeKernel32()
        val applier = JnaWindowsAclApplier(advapi32, kernel32)

        applier.applySddlTo(tempDir, "D:(A;OICI;FA;;;SY)")

        val flags = kernel32.createFileFlags
            ?: throw AssertionError("CreateFileW was not called")
        assertThat(flags and 0x02000000).isEqualTo(0x02000000)
    }

    /** Records the `SetSecurityInfo` call; serves a canned SD→PACL mapping for the rest. */
    private class FakeAdvapi32(
        val sd: Pointer,
        val pAcl: Pointer,
        private val daclPresentValue: Int = 1,
        private val getDaclSucceeds: Boolean = true,
    ) : Advapi32Security {

        var setSecurityInfoDacl: Pointer? = null
        var setSecurityInfoObjectType: Int? = null
        var setSecurityInfoInfo: Int? = null

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

        override fun GetSecurityDescriptorDacl(
            securityDescriptor: Pointer,
            daclPresent: BOOLByReference,
            dacl: PointerByReference,
            daclDefaulted: BOOLByReference?,
        ): Boolean {
            check(securityDescriptor == sd) { "GetSecurityDescriptorDacl got an unexpected descriptor" }
            if (!getDaclSucceeds) return false
            daclPresent.value = BOOL(daclPresentValue.toLong())
            dacl.setValue(if (daclPresentValue == 1) pAcl else null)
            return true
        }

        override fun SetSecurityInfo(
            handle: Pointer,
            objectType: Int,
            securityInfo: Int,
            owner: Pointer?,
            group: Pointer?,
            dacl: Pointer?,
            sacl: Pointer?,
        ): Int {
            setSecurityInfoDacl = dacl
            setSecurityInfoObjectType = objectType
            setSecurityInfoInfo = securityInfo
            return 0
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
