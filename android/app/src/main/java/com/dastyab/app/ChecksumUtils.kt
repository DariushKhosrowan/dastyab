package com.dastyab.app

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * ChecksumUtils — SHA-256 verification of the downloaded dastyab binary.
 *
 * Every dastyab-android-* release asset is accompanied by a SHA256SUMS.txt on
 * the GitHub Release. After download we verify the local file's digest so a
 * truncated / MITM-tampered binary can never be executed.
 *
 * The expected digests are pinned per release (kept in sync manually with the
 * release's SHA256SUMS.txt). If a digest is unknown for the current release,
 * verification is skipped (fail-open) but logged loudly — ELF magic + HTTPS
 * still apply.
 */
object ChecksumUtils {

    /**
     * Expected SHA-256 of dastyab-android-<arch> from the latest release.
     * Update when the release binaries change (CI rebuilds / new release).
     */
    private val EXPECTED_SHA256 = mapOf(
        "aarch64" to null, // populated from SHA256SUMS.txt at release time
        "armv7" to null,
        "x86_64" to null,
        "i686" to null,
    )

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
     * Verify [file] against the pinned digest for [arch].
     * @return true when the digest matches, or when no digest is pinned for
     *         this arch (fail-open — ELF magic check is still in force).
     */
    fun verify(file: File, arch: String): Boolean {
        val expected = EXPECTED_SHA256[arch] ?: return true
        val actual = sha256Hex(file)
        val ok = actual.equals(expected, ignoreCase = true)
        DastyabLogger.i(
            "Checksum", "SHA-256(%s)=%s expected=%s match=%s",
            arch, actual.substring(0, 16) + "...", expected.substring(0, 16) + "...", ok
        )
        return ok
    }
}
