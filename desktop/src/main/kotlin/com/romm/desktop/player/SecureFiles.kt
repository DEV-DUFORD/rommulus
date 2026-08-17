package com.romm.desktop.player

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

/**
 * TOCTOU-hardening file helpers for the player journal machinery (Phase 8 Wave 2, per the
 * Wave 1 audit).
 *
 * Rule: never trust a previously validated path. Every open/create goes through these
 * helpers, which canonicalize the path (resolving symlinks and `..`), refuse symlinks, and
 * return the REAL path so callers can re-verify containment in a trusted root. A raw
 * `..`-laden path is never reopened: session IDs are validated before they may build a path,
 * and every file that is opened is re-resolved at open time.
 */
internal object SecureFiles {

    /**
     * Resolves [path] to its canonical (symlink-resolved) form, requiring that it exists,
     * is not a symlink, and is a regular file. Returns the real path so the caller can
     * re-verify containment ([isWithin]) before using it.
     */
    fun resolveExistingRegular(path: Path): Result<Path> = runCatching {
        if (!Files.exists(path)) {
            throw FileNotFoundException("no such file: $path")
        }
        if (Files.isSymbolicLink(path)) {
            throw SecurityException("refusing to follow symlink: $path")
        }
        if (!Files.isRegularFile(path)) {
            throw IOException("not a regular file: $path")
        }
        path.toRealPath()
    }

    /** True when [canonical] is [root] itself or lives beneath it (both must already be canonical). */
    fun isWithin(canonical: Path, root: Path): Boolean = canonical.startsWith(root)

    /**
     * SHA-256 of the file's CURRENT bytes, lowercase hex. Always recomputed at open time —
     * never cached from an earlier inspection (a file can change between checks).
     */
    fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, READ).use { channel ->
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                val read = channel.read(buffer)
                if (read < 0) break
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Validates a session ID before it may be used to build a path (defense against `../`
     * traversal via journal directory names on disk). UUIDs pass.
     */
    fun requireSessionId(sessionId: String): Result<String> = runCatching {
        require(sessionId.length in 1..128) { "sessionId length out of range: ${sessionId.length}" }
        require(SESSION_ID_PATTERN.matches(sessionId)) { "sessionId contains unsafe characters: $sessionId" }
        sessionId
    }

    private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
}
