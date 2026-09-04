package com.romm.desktop.platform.security

import com.romm.desktop.PosixTestSupport
import com.romm.desktop.storage.paths.WindowsKnownFolderResolver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** Fakeable [WindowsAclApplier] recording every invocation (plans/WINDOWS_IMPL.md §4.7). */
class RecordingAclApplier : WindowsAclApplier {
    val invocations = mutableListOf<Path>()
    var failure: Exception? = null

    override fun applyCurrentUserOnlyAcl(path: Path) {
        val fail = failure
        if (fail != null) throw fail
        invocations.add(path)
    }
}

/** Recording [FileSecurityPolicy] fake for path-level tests. */
class RecordingFileSecurityPolicy : FileSecurityPolicy {
    data class Call(val op: String, val path: Path, val profile: PathPermissionProfile, val sensitivity: FileSensitivity)

    val calls = mutableListOf<Call>()
    var sensitiveHardeningCount = 0
        private set

    override fun ensureDirectory(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity) {
        calls.add(Call("ensureDirectory", path, profile, sensitivity))
        Files.createDirectories(path)
        if (sensitivity == FileSensitivity.SENSITIVE) sensitiveHardeningCount++
    }

    override fun createDirectoryIfAbsent(
        path: Path,
        profile: PathPermissionProfile,
        sensitivity: FileSensitivity,
    ) {
        calls.add(Call("createDirectoryIfAbsent", path, profile, sensitivity))
        if (!Files.exists(path)) Files.createDirectories(path)
        if (sensitivity == FileSensitivity.SENSITIVE) sensitiveHardeningCount++
    }

    override fun hardenFile(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity) {
        calls.add(Call("hardenFile", path, profile, sensitivity))
        if (sensitivity == FileSensitivity.SENSITIVE) sensitiveHardeningCount++
    }
}

class FileSecurityPolicyTest {

    @TempDir
    lateinit var tempDir: Path

    private fun posixOf(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    // ── Linux policy (POSIX mode bits, historical behavior) ─────────────────────

    @Test
    fun `linux policy applies the historical modes on creation`() {
        PosixTestSupport.assumePosixFilesystem(tempDir)
        val policy = LinuxFileSecurityPolicy()
        val dir = tempDir.resolve("dirs")
        val cfg = tempDir.resolve("cfg")
        val file = tempDir.resolve("file.txt")

        policy.createDirectoryIfAbsent(dir, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.SENSITIVE)
        policy.createDirectoryIfAbsent(cfg, PathPermissionProfile.CONFIG_DIRECTORY, FileSensitivity.NORMAL)
        Files.writeString(file, "x")
        policy.hardenFile(file, PathPermissionProfile.USER_ONLY_FILE, FileSensitivity.SENSITIVE)

        assertThat(posixOf(dir)).isEqualTo("rwx------")     // 0700
        assertThat(posixOf(cfg)).isEqualTo("rwxr-xr-x")     // 0755
        assertThat(posixOf(file)).isEqualTo("rw-------")    // 0600
    }

    @Test
    fun `linux policy createDirectoryIfAbsent does not re-harden an existing directory`() {
        PosixTestSupport.assumePosixFilesystem(tempDir)
        val policy = LinuxFileSecurityPolicy()
        val dir = tempDir.resolve("preexisting")
        Files.createDirectories(dir)
        // Owner-set permissions that differ from the profile must be preserved.
        Files.setPosixFilePermissions(dir, setOf(PosixFilePermission.OWNER_READ))

        policy.createDirectoryIfAbsent(dir, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.SENSITIVE)

        assertThat(posixOf(dir)).isEqualTo("r--------")
    }

    @Test
    fun `linux policy ensureDirectory always re-applies the profile`() {
        PosixTestSupport.assumePosixFilesystem(tempDir)
        val policy = LinuxFileSecurityPolicy()
        val dir = tempDir.resolve("reapplied")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, setOf(PosixFilePermission.OWNER_READ))

        policy.ensureDirectory(dir, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.NORMAL)

        assertThat(posixOf(dir)).isEqualTo("rwx------")
    }

    // ── Windows policy (containment + ACL seam; host-neutral) ───────────────────

    @Test
    fun `windows policy hardens contained sensitive paths through the acl seam`() {
        val applier = RecordingAclApplier()
        val policy = WindowsFileSecurityPolicy(listOf(tempDir), applier)
        val dir = tempDir.resolve("RomMulus").resolve("state")

        policy.createDirectoryIfAbsent(dir, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.SENSITIVE)
        Files.writeString(dir.resolve("journal.json"), "{}")
        policy.hardenFile(dir.resolve("journal.json"), PathPermissionProfile.USER_ONLY_FILE, FileSensitivity.SENSITIVE)

        assertThat(Files.isDirectory(dir)).isTrue()
        // Directory + file both went through the seam.
        assertThat(applier.invocations).hasSize(2)
    }

    @Test
    fun `windows policy does not acl normal paths but still creates them`() {
        val applier = RecordingAclApplier()
        val policy = WindowsFileSecurityPolicy(listOf(tempDir), applier)
        val cache = tempDir.resolve("RomMulus").resolve("cache")

        policy.createDirectoryIfAbsent(cache, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.NORMAL)

        assertThat(Files.isDirectory(cache)).isTrue()
        assertThat(applier.invocations).isEmpty()
    }

    @Test
    fun `windows policy rejects paths outside the trusted roots`() {
        val policy = WindowsFileSecurityPolicy(listOf(tempDir), RecordingAclApplier())
        val outside = Files.createTempDirectory("outside_roots")

        assertThatThrownBy {
            policy.createDirectoryIfAbsent(outside.resolve("x"), PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.SENSITIVE)
        }.isInstanceOf(FileSecurityException::class.java)
        // Containment is an invariant even for NORMAL paths.
        Files.writeString(outside.resolve("y.txt"), "x")
        assertThatThrownBy {
            policy.hardenFile(outside.resolve("y.txt"), PathPermissionProfile.USER_ONLY_FILE, FileSensitivity.NORMAL)
        }.isInstanceOf(FileSecurityException::class.java)
    }

    @Test
    fun `windows policy rejects a reparse point at the target`() {
        val linkTarget = tempDir.resolve("real.txt")
        Files.writeString(linkTarget, "x")
        val link = tempDir.resolve("link.txt")
        try {
            Files.createSymbolicLink(link, linkTarget)
        } catch (_: Exception) {
            Assumptions.assumeTrue(false, "symlinks unavailable on this host")
        }
        val policy = WindowsFileSecurityPolicy(listOf(tempDir), RecordingAclApplier())

        assertThatThrownBy {
            policy.hardenFile(link, PathPermissionProfile.USER_ONLY_FILE, FileSensitivity.SENSITIVE)
        }.isInstanceOf(FileSecurityException::class.java)
    }

    @Test
    fun `windows policy fails explicitly when the acl seam cannot establish security`() {
        val failing = RecordingAclApplier().apply { failure = IOException("Win32 error 5: access denied") }
        val policy = WindowsFileSecurityPolicy(listOf(tempDir), failing)

        assertThatThrownBy {
            policy.createDirectoryIfAbsent(
                tempDir.resolve("RomMulus").resolve("data"),
                PathPermissionProfile.USER_ONLY_DIRECTORY,
                FileSensitivity.SENSITIVE,
            )
        }.isInstanceOf(FileSecurityException::class.java)
            .hasCauseInstanceOf(IOException::class.java)
    }

    @Test
    fun `unconfigured acl applier fails with an explicit diagnostic`() {
        assertThatThrownBy { UnconfiguredWindowsAclApplier.applyCurrentUserOnlyAcl(tempDir.resolve("x")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not configured")
    }

    // ── host → policy selection ─────────────────────────────────────────────────

    @Test
    fun `forHost selects the linux policy for linux and macos hosts`() {
        assertThat(FileSecurityPolicies.forHost("Linux")).isInstanceOf(LinuxFileSecurityPolicy::class.java)
        assertThat(FileSecurityPolicies.forHost("Mac OS X")).isInstanceOf(LinuxFileSecurityPolicy::class.java)
        // Unknown hosts cannot start a production build; the conservative POSIX policy applies.
        assertThat(FileSecurityPolicies.forHost("Solaris")).isInstanceOf(LinuxFileSecurityPolicy::class.java)
    }

    @Test
    fun `forHost selects the windows policy rooted at the known folders`() {
        val appData = tempDir.resolve("appdata")
        val localAppData = tempDir.resolve("localappdata")
        val policy = FileSecurityPolicies.forHost(
            "Windows 11",
            mapOf("APPDATA" to appData.toString(), "LOCALAPPDATA" to localAppData.toString()),
        )

        assertThat(policy).isInstanceOf(WindowsFileSecurityPolicy::class.java)
    }

    @Test
    fun `forHost fails explicitly when windows known folders are missing`() {
        // Containment roots resolve lazily (JNA/native work stays deferred until first use —
        // plans/WINDOWS_IMPL.md §3.4), so the refusal surfaces on the first path operation
        // rather than at construction.
        val policy = FileSecurityPolicies.forHost("Windows 11", emptyMap())
        assertThatThrownBy {
            policy.createDirectoryIfAbsent(
                tempDir.resolve("state"),
                PathPermissionProfile.USER_ONLY_DIRECTORY,
                FileSensitivity.NORMAL,
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("APPDATA")
    }

    @Test
    fun `forWindows builds the policy from the given resolver and applier`() {
        // The explicit startup seam: roots come from the same resolver instance startup passes
        // to WindowsAppPaths, and sensitive hardening flows to the injected Win32 applier.
        val appData = tempDir.resolve("appdata")
        val localAppData = tempDir.resolve("localappdata")
        val folders = object : WindowsKnownFolderResolver {
            override fun roamingAppData(): Path = appData
            override fun localAppData(): Path = localAppData
        }
        val applier = RecordingAclApplier()

        val policy = FileSecurityPolicies.forWindows(folders, applier)
        val target = appData.resolve("RomMulus").resolve("state")
        policy.createDirectoryIfAbsent(target, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.SENSITIVE)

        assertThat(policy).isInstanceOf(WindowsFileSecurityPolicy::class.java)
        assertThat(applier.invocations).containsExactly(target)
    }
}
