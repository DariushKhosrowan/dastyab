package com.dastyab.app

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BinaryUtilsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = java.io.File.createTempFile("binary-test-", ".dir").apply { delete() }
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `valid ELF binary returns true`() {
        val elfFile = File(tempDir, "valid-elf")
        // Pad to > 4096 bytes so it passes the min-size check.
        writeBytes(elfFile, ELF_MAGIC + ByteArray(4096, { it.toByte() }))
        elfFile.setExecutable(true)
        assertTrue(BinaryUtils.isValidElfBinary(elfFile))
    }

    @Test
    fun `empty file is not valid ELF`() {
        val emptyFile = File(tempDir, "empty")
        emptyFile.createNewFile()
        assertFalse(BinaryUtils.isValidElfBinary(emptyFile))
    }

    @Test
    fun `non-existent file is not valid ELF`() {
        val missing = File(tempDir, "does-not-exist")
        assertFalse(BinaryUtils.isValidElfBinary(missing))
    }

    @Test
    fun `file with HTML content is not valid ELF`() {
        val htmlFile = File(tempDir, "fake-elf")
        writeString(htmlFile, "<html><body>Not an ELF binary</body></html>")
        assertFalse(BinaryUtils.isValidElfBinary(htmlFile))
    }

    @Test
    fun `file with ELF magic but too small is not valid`() {
        val smallElf = File(tempDir, "small-elf")
        writeBytes(smallElf, ELF_MAGIC) // only 4 bytes
        assertFalse(BinaryUtils.isValidElfBinary(smallElf))
    }

    @Test
    fun `file with wrong magic is not valid ELF`() {
        val wrongMagic = File(tempDir, "wrong-magic")
        writeBytes(wrongMagic, byteArrayOf(0x7f.toByte(), 'X'.toByte(), 'L'.toByte(), 'F'.toByte()) +
            ByteArray(4092)) // pad to > 4096
        assertFalse(BinaryUtils.isValidElfBinary(wrongMagic))
    }

    private fun writeBytes(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes) }
    }

    private fun writeString(file: File, text: String) {
        writeBytes(file, text.toByteArray())
    }

    companion object {
        /** ELF magic number: \\x7fELF */
        private val ELF_MAGIC = byteArrayOf(0x7f.toByte(), 'E'.toByte(), 'L'.toByte(), 'F'.toByte())
    }
}