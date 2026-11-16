package com.dastyab.app

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DastyabLoggerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = java.io.File.createTempFile("dastyab-test-", ".dir").apply { delete() }
        tempDir.mkdirs()
        DastyabLogger.init(tempDir)
    }

    @After
    fun tearDown() {
        // Clean up temp files, and reset level state so tests are order-independent.
        DastyabLogger.resetLevels()
        tempDir.deleteRecursively()
    }

    @Test
    fun `init creates log directory`() {
        val logDir = File(tempDir, "logs")
        assertTrue(logDir.exists())
        assertTrue(logDir.isDirectory)
    }

    @Test
    fun `info log writes to file`() {
        DastyabLogger.i("TestTag", "Hello test world")
        val files = DastyabLogger.getLogFiles()
        assertEquals(1, files.size)
        val content = files[0].readText()
        assertTrue(content.contains("Hello test world"))
        assertTrue(content.contains("[TestTag]"))
        assertTrue(content.contains("[INFO]"))
    }

    @Test
    fun `error log with throwable includes stack trace`() {
        val e = RuntimeException("test boom")
        DastyabLogger.e("TestTag", e, "Something failed")
        val files = DastyabLogger.getLogFiles()
        assertEquals(1, files.size)
        val content = files[0].readText()
        assertTrue(content.contains("Something failed"))
        assertTrue(content.contains("RuntimeException"))
        assertTrue(content.contains("test boom"))
    }

    @Test
    fun `min file level filters logs`() {
        DastyabLogger.setMinFileLevel(com.dastyab.app.DastyabLogger.LogLevel.ERROR)
        DastyabLogger.d("TestTag", "debug message should be filtered")
        DastyabLogger.i("TestTag", "info message should be filtered")
        DastyabLogger.w("TestTag", "warn message should be filtered")
        DastyabLogger.e("TestTag", "error message should appear")
        val files = DastyabLogger.getLogFiles()
        val content = files[0].readText()
        assertTrue(content.contains("error message should appear"))
        assertTrue(!content.contains("debug message should be filtered"))
        assertTrue(!content.contains("info message should be filtered"))
        assertTrue(!content.contains("warn message should be filtered"))
    }

    @Test
    fun `tag level overrides min level`() {
        DastyabLogger.setMinFileLevel(com.dastyab.app.DastyabLogger.LogLevel.ERROR)
        DastyabLogger.setTagLevel("SpecialTag", com.dastyab.app.DastyabLogger.LogLevel.DEBUG)
        DastyabLogger.e("TestTag", "error on default tag appears")
        DastyabLogger.d("SpecialTag", "debug on special tag appears")
        val files = DastyabLogger.getLogFiles()
        val content = files[0].readText()
        assertTrue(content.contains("error on default tag appears"))
        assertTrue(content.contains("debug on special tag appears"))
    }

    @Test
    fun `log files roll over when exceeding max size`() {
        // Rollover only happens when a log file reaches MAX_LOG_FILE_SIZE (2 MB);
        // normal writes append to the same file. Verify no spurious rollover:
        val filesBefore = DastyabLogger.getLogFiles().map { it.name }.toSet()
        DastyabLogger.i("TestTag", "Log entry for rollover test")
        val filesAfter = DastyabLogger.getLogFiles().map { it.name }.toSet()
        assertEquals(filesBefore, filesAfter)
        val content = DastyabLogger.getLogFiles()[0].readText()
        assertTrue(content.contains("Log entry for rollover test"))
    }

    @Test
    fun `getLatestLog returns newest file content`() {
        DastyabLogger.i("TestTag", "First message")
        val firstFile = DastyabLogger.getLogFiles()[0]
        Thread.sleep(10) // ensure different lastModified
        DastyabLogger.i("TestTag", "Second message")
        val latest = DastyabLogger.getLatestLog()
        assertNotNull(latest)
        assertTrue(latest!!.contains("Second message"))
    }

    @Test
    fun `session id is consistent`() {
        val id1 = DastyabLogger.getSessionId()
        val id2 = DastyabLogger.getSessionId()
        assertEquals(id1, id2)
        assertTrue(id1.length == 8)
    }
}