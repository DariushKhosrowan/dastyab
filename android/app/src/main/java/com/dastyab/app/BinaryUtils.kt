package com.dastyab.app

import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * BinaryUtils — verify that a downloaded file is a valid ELF executable
 * before marking the app as "ready".
 *
 * Guards against partial/corrupt downloads that would otherwise fail
 * with an opaque "cannot execute" error at runtime.
 */
object BinaryUtils {

    /** ELF magic number: \x7fELF */
    private val ELF_MAGIC = byteArrayOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    /**
     * Returns true if [file] exists, is executable, and starts with the
     * ELF magic number (i.e. it's a real binary, not a truncated HTML
     * error page).
     */
    fun isValidElfBinary(file: File): Boolean {
        return file.exists()
            && file.canExecute()
            && file.length() > 4096  // must be at least 4 KB
            && hasElfMagic(file)
    }

    private fun hasElfMagic(file: File): Boolean {
        var fis: FileInputStream? = null
        return try {
            fis = FileInputStream(file)
            val header = ByteArray(4)
            val read = fis.read(header)
            read == 4 && header.contentEquals(ELF_MAGIC)
        } catch (_: IOException) {
            false
        } finally {
            fis?.close()
        }
    }
}