@file:Suppress("UsableSpace") // See note on AtomicFileStore.hasSufficientSpace below.

package com.romm.androidtv.cache

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Streams an authenticated HTTP download straight to a same-filesystem temp
 * file, hashing every byte as it arrives, then only ever exposes the content
 * under its final name once every check has passed (LIBRETRO_REFACTOR.md
 * section 10, "Download pipeline"). A partially-downloaded or
 * corrupted/mismatched file is *never* renamed into place, so a crash or
 * network failure mid-download can never leave something launchable behind.
 *
 * All hashing happens in the same pass as the streaming write — no second
 * read-through of the file is needed.
 */
object AtomicFileStore {

    /** Suffix marking a file as an in-progress, not-yet-verified download. */
    const val TEMP_SUFFIX = ".part"

    /** Digest algorithm names this store knows how to compute during a single streamed pass. */
    const val SHA256 = "SHA-256"
    const val SHA1 = "SHA-1"

    sealed interface DownloadOutcome {
        /**
         * The file now exists at [file] (inside [destinationDir], named [finalFileName]),
         * every requested digest matched (or had nothing to compare against), and the
         * byte count matched [DownloadRequest.expectedSizeBytes] when that was known.
         */
        data class Success(
            val file: File,
            val sizeBytes: Long,
            /** Hex-encoded digests, keyed by algorithm name (e.g. [SHA256], [SHA1]). */
            val digests: Map<String, String>,
        ) : DownloadOutcome

        data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : DownloadOutcome
        data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : DownloadOutcome
        data class HashMismatch(val algorithm: String, val expectedHash: String, val actualHash: String) : DownloadOutcome
        data class HttpError(val code: Int) : DownloadOutcome
        data class NetworkError(val message: String) : DownloadOutcome
    }

    data class DownloadRequest(
        val client: OkHttpClient,
        val url: String,
        /** Directory the temp and final files live in — must be on the same filesystem for an atomic rename. */
        val destinationDir: File,
        val finalFileName: String,
        /** Reject the download if the server-declared or actual size exceeds this. Null = no cap. */
        val maxExpectedSizeBytes: Long? = null,
        /** Exact expected size from trusted metadata, if known ahead of time. Null = accept whatever arrives. */
        val expectedSizeBytes: Long? = null,
        /** Expected hex digests to verify against, keyed by algorithm name. Empty entries are skipped. */
        val expectedDigests: Map<String, String> = emptyMap(),
        /** Which digests to compute even if not being verified (e.g. SHA-256 is always our identity hash). */
        val digestsToCompute: Set<String> = setOf(SHA256),
    )

    /**
     * Executes [request], streaming the response body to a temp file in
     * [DownloadRequest.destinationDir], then atomically installing it as
     * [DownloadRequest.finalFileName] only if every check passes. On any
     * failure the temp file is deleted before returning — no partial or
     * mismatched file is ever left where a caller might find it under its
     * final name.
     */
    fun download(request: DownloadRequest): DownloadOutcome {
        request.destinationDir.mkdirs()
        val tempFile = File(request.destinationDir, "${request.finalFileName}$TEMP_SUFFIX")
        // Never resume a stale partial download from a previous attempt — always start clean.
        tempFile.delete()

        val httpRequest = Request.Builder().url(request.url).get().build()

        return try {
            request.client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return DownloadOutcome.HttpError(response.code)
                }
                val body = response.body ?: return DownloadOutcome.NetworkError("empty response body")

                val declaredLength = body.contentLength().takeIf { it >= 0 }
                val cap = request.maxExpectedSizeBytes ?: request.expectedSizeBytes
                if (cap != null && declaredLength != null && declaredLength > cap) {
                    return DownloadOutcome.SizeMismatch(cap, declaredLength)
                }

                val requiredEstimate = declaredLength ?: request.expectedSizeBytes ?: 0L
                val available = request.destinationDir.usableSpace
                if (requiredEstimate > 0 && available < requiredEstimate) {
                    return DownloadOutcome.InsufficientSpace(requiredEstimate, available)
                }

                val digesters = request.digestsToCompute
                    .plus(request.expectedDigests.keys)
                    .associateWith { MessageDigest.getInstance(it) }

                var totalBytes = 0L
                val buffer = ByteArray(64 * 1024)
                FileOutputStream(tempFile).use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            totalBytes += read
                            val overallCap = request.maxExpectedSizeBytes ?: request.expectedSizeBytes
                            if (overallCap != null && totalBytes > overallCap) {
                                return DownloadOutcome.SizeMismatch(overallCap, totalBytes)
                            }
                            out.write(buffer, 0, read)
                            for (digester in digesters.values) digester.update(buffer, 0, read)
                        }
                    }
                    out.fd.sync()
                }

                if (request.expectedSizeBytes != null && totalBytes != request.expectedSizeBytes) {
                    return DownloadOutcome.SizeMismatch(request.expectedSizeBytes, totalBytes)
                }

                val digests = digesters.mapValues { (_, digester) -> digester.digest().toHexString() }
                for ((algorithm, expected) in request.expectedDigests) {
                    if (expected.isBlank()) continue
                    val actual = digests[algorithm]
                    if (actual != null && !actual.equals(expected, ignoreCase = true)) {
                        return DownloadOutcome.HashMismatch(algorithm, expected, actual)
                    }
                }

                val finalFile = File(request.destinationDir, request.finalFileName)
                if (!tempFile.renameTo(finalFile)) {
                    return DownloadOutcome.NetworkError("atomic rename failed")
                }

                DownloadOutcome.Success(finalFile, totalBytes, digests)
            }
        } catch (e: IOException) {
            DownloadOutcome.NetworkError(e.message ?: e.javaClass.simpleName)
        } finally {
            // Whatever happened, a leftover temp file must never remain reachable as "the" file.
            tempFile.delete()
        }
    }

    /**
     * Deletes any leftover `*.part` files under [root] (recursively). Call this once at
     * process/cache startup to reconcile temp files orphaned by a process death mid-download
     * (LIBRETRO_REFACTOR.md section 10, "Cache identity and eviction" — reconcile orphaned
     * temporary files after process death). Safe to call even if no download is in flight,
     * since a live download always deletes its own temp file in its `finally` block above —
     * only a killed process can leave one behind.
     */
    fun sweepOrphanTempFiles(root: File): Int {
        if (!root.exists()) return 0
        var deleted = 0
        root.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(TEMP_SUFFIX) && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    /**
     * Whether [destinationDir]'s filesystem currently reports at least [requiredBytes] free.
     *
     * Uses plain [File.getUsableSpace] rather than `StorageManager#getAllocatableBytes`/
     * `allocateBytes` (which additionally consider space the OS could reclaim from other
     * apps' clearable caches): those APIs require an Android [android.content.Context],
     * which would pull framework/Robolectric dependencies into this class and the plain-JVM
     * unit tests that exercise it. This is a deliberate, documented trade-off, not an
     * oversight — a future caller with a `Context` handy is free to pass a more generous
     * pre-computed [requiredBytes] margin, or to add a `Context`-aware overload later.
     */
    fun hasSufficientSpace(destinationDir: File, requiredBytes: Long): Boolean {
        destinationDir.mkdirs()
        return destinationDir.usableSpace >= requiredBytes
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
