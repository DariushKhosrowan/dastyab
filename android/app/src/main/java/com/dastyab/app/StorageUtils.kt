package com.dastyab.app

import android.os.Environment
import android.os.StatFs

/**
 * StorageUtils — free-space checks before downloading the binary.
 *
 * Prevents cryptic ENOSPC failures mid-download by verifying enough
 * free space exists up front.
 */
object StorageUtils {

    /// The dastyab Android binary is ~10-20 MB compressed; require 50 MB free.
    private const val REQUIRED_FREE_BYTES = 50L * 1024 * 1024

    /**
     * Returns true if the app's internal storage has enough free space
     * to download and store the dastyab binary.
     */
    fun hasEnoughSpace(): Boolean {
        return hasEnoughSpaceFor(REQUIRED_FREE_BYTES)
    }

    /**
     * Returns true if [requiredBytes] of free space is available on the
     * app's internal storage. Used to prevent ENOSPC failures mid-download.
     */
    fun hasEnoughSpaceFor(requiredBytes: Long): Boolean {
        try {
            val fs = StatFs(Environment.getDataDirectory().path)
            val freeBytes: Long
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
                freeBytes = fs.availableBlocksLong * fs.blockSizeLong
            } else {
                freeBytes = fs.availableBlocks.toLong() * fs.blockSize.toLong()
            }
            return freeBytes >= requiredBytes
        } catch (_: Exception) {
            // If we can't check, assume there's enough space and let the
            // download fail naturally rather than blocking the user.
            return true
        }
    }
}