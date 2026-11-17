package com.dastyab.app

import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ChecksumUtils — SHA-256 verification of the downloaded dastyab binary.
 *
 * Every dastyab-android-* release asset is accompanied by a SHA256SUMS.txt on
 * the GitHub Release. After download we verify the local file's digest so a
 * truncated / MITM-tampered binary can never be executed.
 *
 * The expected digests are fetched dynamically from the GitHub release's
 * SHA256SUMS.txt at runtime. If the fetch fails (network issues), verification
 * is skipped (fail-open) but logged loudly — ELF magic + HTTPS still apply.
 */
object ChecksumUtils {

    private const val SHA256SUMS_URL =
        "https://github.com/DastYab/dastyab/releases/latest/download/SHA256SUMS.txt"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Expected SHA-256 of dastyab-android-<arch> from the latest release.
     * These are pinned per release if known; otherwise fetched dynamically.
     */
    private val EXPECTED_SHA256 = mapOf(
        "aarch64" to null as String?,
        "armv7" to null as String?,
        "x86_64" to null as String?,
        "i686" to null as String?,
    )

    // Session-level cache for dynamically fetched hashes
    private val fetchedHashCache = mutableMapOf<String, String?>()
    private var hashFetchAttempted = false

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var read = fis.read(buf)
            while (read > 0) {
                md.update(buf, 0, read)
                read = fis.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Fetch the expected SHA-256 for [arch] from the GitHub release's
     * SHA256SUMS.txt. Caches the result for the session.
     *
     * @return the hex hash, or null if the fetch fails or the arch is not found.
     */
    fun fetchExpectedSha256(arch: String): String? {
        synchronized(fetchedHashCache) {
            if (arch in fetchedHashCache) {
                return fetchedHashCache[arch]
            }
        }

        return try {
            DastyabLogger.i("Checksum", "Fetching SHA256SUMS.txt from GitHub releases")
            val conn = URL(SHA256SUMS_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "DastyabAndroid/1.2")

            val code = conn.responseCode
            if (code != 200) {
                DastyabLogger.w("Checksum", "SHA256SUMS.txt fetch returned HTTP %d — fail-open", code)
                conn.disconnect()
                synchronized(fetchedHashCache) { fetchedHashCache[arch] = null }
                return null
            }

            val content = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            DastyabLogger.d("Checksum", "SHA256SUMS.txt content:\n%s", content)

            // Parse lines: "<hash>  <filename>"
            // Look for dastyab-android-<arch>
            val target = "dastyab-android-$arch"
            var foundHash: String? = null
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                // Format: "<64-hex-chars>  <filename>" or "<64-hex-chars> *<filename>"
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    val hash = parts[0].trim()
                    val filename = parts[1].trim().removePrefix("*")
                    if (filename == target && hash.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                        foundHash = hash.lowercase()
                        break
                    }
                }
            }

            if (foundHash != null) {
                DastyabLogger.i("Checksum", "Found expected SHA-256 for %s: %s...",
                    arch, foundHash.substring(0, 16))
            } else {
                DastyabLogger.w("Checksum", "No SHA-256 entry found for %s in SHA256SUMS.txt — fail-open", arch)
            }

            synchronized(fetchedHashCache) { fetchedHashCache[arch] = foundHash }
            foundHash
        } catch (e: Exception) {
            DastyabLogger.e("Checksum", e,
                "Failed to fetch SHA256SUMS.txt — fail-open (network error)")
            synchronized(fetchedHashCache) { fetchedHashCache[arch] = null }
            null
        }
    }

    /**
     * Verify [file] against the expected digest for [arch].
     *
     * If a digest is pinned in [EXPECTED_SHA256], use it. Otherwise, attempt to
     * fetch the digest dynamically from the GitHub release's SHA256SUMS.txt.
     * If the fetch fails (network issues), verification is skipped (fail-open)
     * but logged loudly — ELF magic + HTTPS still apply.
     *
     * @return true when the digest matches, or when no digest could be
     *         obtained (fail-open — ELF magic check is still in force).
     */
    fun verify(file: File, arch: String): Boolean {
        val pinned = EXPECTED_SHA256[arch]
        val expected = pinned ?: fetchExpectedSha256(arch)

        if (expected == null) {
            DastyabLogger.w("Checksum",
                "No SHA-256 digest available for arch=%s (fail-open) — ELF magic + HTTPS still apply", arch)
            return true
        }

        val actual = sha256Hex(file)
        val ok = actual.equals(expected, ignoreCase = true)
        DastyabLogger.i(
            "Checksum", "SHA-256(%s)=%s expected=%s match=%s",
            arch, actual.substring(0, 16) + "...", expected.substring(0, 16) + "...", ok
        )
        return ok
    }
}
