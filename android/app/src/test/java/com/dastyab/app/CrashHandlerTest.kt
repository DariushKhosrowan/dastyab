package com.dastyab.app

import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrashHandlerTest {

    private lateinit var tempDir: File
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(null)
        tempDir = java.io.File.createTempFile("crash-test-", ".dir").apply { delete() }
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        tempDir.deleteRecursively()
    }

    @Test
    fun `reinstall does not chain handler to itself`() {
        CrashHandler.install(mockCtx(), tempDir)
        val first = Thread.getDefaultUncaughtExceptionHandler()
        CrashHandler.install(mockCtx(), tempDir)
        assertTrue(first === Thread.getDefaultUncaughtExceptionHandler())
    }

    /** A harmless mock Context — CrashHandler only passes it through to the logger. */
    private fun mockCtx(): android.content.Context = mockk(relaxed = true)

    @Test
    fun `crash directory is created on install`() {
        CrashHandler.install(mockCtx(), tempDir)
        val crashDir = File(tempDir, "crashes")
        assertTrue(crashDir.exists(), "crashes dir should be created by install()")
        assertTrue(crashDir.isDirectory)
    }

    @Test
    fun `getCrashFiles returns empty list when no crashes`() {
        CrashHandler.install(mockCtx(), tempDir)
        val files = CrashHandler.getCrashFiles()
        assertNotNull(files)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `uncaught exception handler is installed`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        CrashHandler.install(mockCtx(), tempDir)
        val after = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(after)
        assertFalse(after === before, "default handler should be replaced by CrashHandler")
    }
}
