package com.dastyab.app

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * BinaryUpdater — checks whether a newer dastyab binary is available on
 * GitHub Releases by comparing the local binary's SHA-256 against the
 * release's SHA256SUMS.txt.
 *
 * Result of [checkForUpdate]:
 *  - [UpdateStatus.UP_TO_DATE] — local digest matches the release digest.
 *  - [UpdateStatus.UPDATE_AVAILABLE] — digests differ; re-download needed.
 *  - [UpdateStatus.UNKNOWN] — could not determine (no network, HTTP error,
 *    no SHA256SUMS entry). Never a hard failure.
 */
object BinaryUpdater {

    private const val SHA256SUMS_URL =
        "https://github.com/DastYab/dastyab/releases/latest/download/SHA256SUMS.txt"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    enum class UpdateStatus { UP_TO_DATE, UPDATE_AVAILABLE, UNKNOWN }

    data class Result(
        val status: UpdateStatus,
        val message: String
    )

    /**
     * Compare the local binary at [binary] with the latest release's
     * SHA256SUMS.txt for [arch]. Blocking network call — run on a worker thread.
     */
    fun checkForUpdate(binary: File, arch: String): Result {
        if (!binary.exists()) {
            return Result(UpdateStatus.UPDATE_AVAILABLE, "Binary missing — download required")
        }
        return try {
            val localHash = ChecksumUtils.sha256Hex(binary)
            val remoteHash = fetchReleaseHash(arch)
            if (remoteHash == null) {
                Result(UpdateStatus.UNKNOWN, "Could not fetch release checksum")
            } else if (localHash.equals(remoteHash, ignoreCase = true)) {
                DastyabLogger.i("BinaryUpdater", "Binary is up to date (%s...)",
                    localHash.substring(0, 16))
                Result(UpdateStatus.UP_TO_DATE, "Binary is up to date")
            } else {
                DastyabLogger.i("BinaryUpdater",
                    "Update available — local=%s... remote=%s...",
                    localHash.substring(0, 16), remoteHash.substring(0, 16))
                Result(UpdateStatus.UPDATE_AVAILABLE, "A new version is available")
            }
        } catch (e: Exception) {
            DastyabLogger.e("BinaryUpdater", e, "Update check failed")
            Result(UpdateStatus.UNKNOWN, "Update check failed: ${e.message ?: e.toString()}")
        }
    }

    /** Fetch the release hash for dastyab-android-<arch> from SHA256SUMS.txt. */
    private fun fetchReleaseHash(arch: String): String? {
        val conn = URL(SHA256SUMS_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "DastyabAndroid/1.3")
        try {
            val code = conn.responseCode
            if (code != 200) {
                DastyabLogger.w("BinaryUpdater", "SHA256SUMS.txt HTTP %d", code)
                return null
            }
            val content = conn.inputStream.bufferedReader().readText()
            val target = "dastyab-android-$arch"
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    val hash = parts[0].trim()
                    val filename = parts[1].trim().removePrefix("*")
                    if (filename == target && hash.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                        return hash.lowercase()
                    }
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }
}
