package com.romm.desktop.platform.security

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test-only [FileSecurityPolicy] installed by [TestFileSecurityPolicyExtension] for every
 * desktop test (plans/WINDOWS_IMPL.md §4.7): real directory creation + the historical POSIX
 * mode bits when the hosting filesystem supports them, and a silent no-op otherwise (NTFS).
 *
 * This is what makes the suite host-neutral. On a Windows CI runner the process-default
 * [FileSecurityPolicies.default] is the fail-closed Windows policy — containment in the
 * current user's `%APPDATA%`/`%LOCALAPPDATA%` roots plus current-user-only NTFS ACLs via the
 * startup-wired applier. That behavior is exactly right for production, but wrong for tests:
 * they manage JUnit temp dirs and must never run live containment/ACL hardening (and cannot —
 * the unconfigured applier refuses every sensitive path by design). Production code never
 * sees this policy: it is reachable only through the [FileSecurityPolicies.testPolicyOverride]
 * seam that the test extension sets, so the production fail-closed behavior is untouched.
 *
 * SENSITIVE paths are deliberately NOT refused on non-POSIX filesystems (unlike
 * [LinuxFileSecurityPolicy]): a test host without POSIX bits still has to run the suite, and
 * the hardening semantics themselves are covered by [FileSecurityPolicyTest] with explicit
 * policies and applier fakes.
 */
object TestFileSecurityPolicy : FileSecurityPolicy {

    override fun ensureDirectory(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity) {
        Files.createDirectories(path)
        applyPosixWhenSupported(path, profile)
    }

    override fun createDirectoryIfAbsent(
        path: Path,
        profile: PathPermissionProfile,
        sensitivity: FileSensitivity,
    ) {
        if (!Files.exists(path)) {
            Files.createDirectories(path)
            applyPosixWhenSupported(path, profile)
        }
    }

    override fun hardenFile(path: Path, profile: PathPermissionProfile, sensitivity: FileSensitivity) {
        applyPosixWhenSupported(path, profile)
    }

    private fun applyPosixWhenSupported(path: Path, profile: PathPermissionProfile) {
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, profile.posixPermissions())
        }
    }
}

/**
 * JUnit 5 extension auto-registered for the whole desktop test module via
 * `META-INF/services/org.junit.jupiter.api.extension.Extension` in the test resources: it
 * installs [TestFileSecurityPolicy] as the process-default policy for the duration of each
 * test and restores host selection afterwards. See [TestFileSecurityPolicy] for why the
 * suite needs this on a real Windows host.
 */
class TestFileSecurityPolicyExtension : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        FileSecurityPolicies.testPolicyOverride = TestFileSecurityPolicy
    }

    override fun afterEach(context: ExtensionContext) {
        FileSecurityPolicies.testPolicyOverride = null
    }
}
