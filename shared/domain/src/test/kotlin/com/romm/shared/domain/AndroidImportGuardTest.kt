package com.romm.shared.domain

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Architecture guard: rejects Android framework imports in shared modules.
 *
 * Enforces the Linux port plan (plans/LINUX_X64.md) Phase 0 work item #6:
 * "Add an architecture test rejecting Android imports in shared modules."
 *
 * The Linux desktop port must share domain, network, storage-api, and
 * presentation code with Android without pulling in any `android.*` or
 * `androidx.*` types.  Violations block the build so that platform-specific
 * code is pushed into adapter layers rather than leaking into shared modules.
 *
 * Allowlist design — `shared/ui` is intentionally excluded from the scan.
 * `:shared:ui` is the designated Compose Multiplatform UI module where
 * `androidx.compose.*` imports are expected on desktop.  It will be gated
 * by its own review when UI sharing begins (Phase 6).
 */
class AndroidImportGuardTest {

    /**
     * Shared modules scanned for Android import violations.
     *
     * `shared/ui` is deliberately excluded: it is the Compose Multiplatform
     * UI module where `androidx.compose.*` imports are expected on desktop.
     * Its Android-surface dependencies will be gated by a separate review
     * when UI sharing begins (Phase 6 of plans/LINUX_X64.md).
     */
    private val SCANNED_MODULES = listOf("domain", "network", "storage-api", "presentation")

    /**
     * Regex patterns that flag an Android-framework token on a single line.
     *
     * Catches BOTH imported references (via `import android.*`) AND fully-
     * qualified usages (e.g. `android.content.Context` used inline without
     * an import). This is intentional: the Linux port plan bans ALL
     * `android.*` / `androidx.*` usage in shared modules, not just imports.
     *
     * Acceptable false-positive: a doc comment or string literal that
     * happens to mention "android.something" will also trip the guard.
     * Erring on the side of strictness is correct for this architecture guard.
     *
     * - `import android\.` — any android.* import statement
     * - `import androidx\.` — any androidx.* import statement
     * - `android\.` — any fully-qualified android.* token (also matches imports, redundant but complete)
     * - `androidx\.` — any fully-qualified androidx.* token (also matches imports, redundant but complete)
     */
    private val FORBIDDEN_PATTERNS = listOf(
        Regex("""import android\.[a-zA-Z]"""),
        Regex("""import androidx\.[a-zA-Z]"""),
        Regex("""android\.[a-zA-Z]"""),
        Regex("""androidx\.[a-zA-Z]""")
    )

    /**
     * Locate the repository [shared/] directory from the Gradle test working
     * directory, which is the module directory (e.g. `shared/domain`).
     */
    private fun resolveSharedDir(): File {
        // Gradle runs tests with workingDir = module dir (shared/domain)
        val candidate = File(System.getProperty("user.dir")).canonicalFile

        // Walk upward at most 5 levels looking for a directory that contains "shared/"
        var current: File = candidate
        var found: File? = null
        var depth = 0
        while (depth < 5) {
            val shared = File(current, "shared")
            if (shared.isDirectory) {
                found = shared
                break
            }
            val parent = current.parentFile
            if (parent == null) break
            current = parent
            depth++
        }

        if (found != null) return found

        throw AssertionError(
            "AndroidImportGuardTest: could not locate shared/ directory. " +
                "Searched from ${candidate.canonicalPath} upward 5 levels. " +
                "Ensure this test runs within the romm-android-tv repository."
        )
    }

    /**
     * Collect all *.kt files under shared/<module>/src/main/, skipping build/ and .git directories.
     */
    private fun collectKotlinFiles(sharedDir: File): List<File> {
        val files = mutableListOf<File>()
        for (moduleName in SCANNED_MODULES) {
            val mainSrc = File(sharedDir, "$moduleName/src/main")
            if (!mainSrc.isDirectory) continue
            mainSrc.walkTopDown()
                .onEnter { !it.name.equals("build", ignoreCase = true) && !it.name.startsWith(".") }
                .filter { it.extension == "kt" }
                .forEach(files::add)
        }
        return files
    }

    @Test
    fun `no Android imports in shared modules`() {
        val sharedDir = resolveSharedDir()
        val kotlinFiles = collectKotlinFiles(sharedDir)

        if (kotlinFiles.isEmpty()) {
            throw AssertionError(
                "AndroidImportGuardTest: found zero .kt files under shared/*/src/main/. " +
                    "Scanned modules: ${SCANNED_MODULES.joinToString()}. " +
                    "The shared/ directory root is: ${sharedDir.canonicalPath}"
            )
        }

        val violations = mutableListOf<String>()

        for (file in kotlinFiles) {
            val relativePath = file.relativeTo(sharedDir).path
            val lines = file.readLines()
            for (index in lines.indices) {
                val lineNumber = index + 1
                val line = lines[index]
                for (pattern in FORBIDDEN_PATTERNS) {
                    if (pattern.containsMatchIn(line)) {
                        violations.add("$relativePath:$lineNumber: $line.trim()")
                        break // one violation per line is sufficient
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            val report = buildString {
                appendLine("AndroidImportGuardTest FAILED — ${violations.size} Android import violation(s) in shared modules:")
                appendLine()
                for (v in violations) {
                    appendLine("  ✗ $v")
                }
                appendLine()
                appendLine("These imports must be removed or moved into an Android adapter module.")
                appendLine("Reference: plans/LINUX_X64.md Phase 0 work item #6")
            }
            throw AssertionError(report)
        }
    }
}
