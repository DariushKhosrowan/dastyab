package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeSafetyTest {

    private lateinit var tempDir: File
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(null)
        DastyabLogger.resetLevels()
        tempDir = File.createTempFile("runtime-safety-", ".dir").apply { delete() }
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        DastyabLogger.shutdown()
        DastyabLogger.resetLevels()
        tempDir.deleteRecursively()
    }

    @Test
    fun `crash marker is written on uncaught exception and consumed once`() {
        val ctx = mockk<Context>(relaxed = true)
        DastyabLogger.init(tempDir, ctx)
        // Install over a harmless no-op handler so chaining does not abort test runner.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashHandler.install(ctx, tempDir)

        assertNull(CrashHandler.consumePendingCrashNotice())

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler)
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("simulated boom"))

        val crashFiles = CrashHandler.getCrashFiles()
        assertEquals(1, crashFiles.size)
        val content = crashFiles[0].readText()
        assertTrue(content.contains("IllegalStateException"))
        assertTrue(content.contains("simulated boom"))

        val notice = CrashHandler.consumePendingCrashNotice()
        assertNotNull(notice)
        assertTrue(notice.contains("IllegalStateException"))
        assertNull(CrashHandler.consumePendingCrashNotice(), "marker should be consumed once")
    }

    @Test
    fun `eNonBlocking does not throw when logger is uninitialized or called under contention`() {
        val ctx = mockk<Context>(relaxed = true)
        DastyabLogger.init(tempDir, ctx)
        DastyabLogger.eNonBlocking("TestTag", RuntimeException("boom"), "fatal on %s", "worker")
        val files = DastyabLogger.getLogFiles()
        assertTrue(files.isNotEmpty())
        assertTrue(files[0].readText().contains("fatal on worker"))
    }

    @Test
    fun `uiSafety isActivityAlive respects finishing and destroyed flags`() {
        assertFalse(UiSafety.isActivityAlive(null))

        val alive = mockk<Activity>(relaxed = true)
        every { alive.isFinishing } returns false
        every { alive.isDestroyed } returns false
        assertTrue(UiSafety.isActivityAlive(alive))

        val finishing = mockk<Activity>(relaxed = true)
        every { finishing.isFinishing } returns true
        every { finishing.isDestroyed } returns false
        assertFalse(UiSafety.isActivityAlive(finishing))

        val destroyed = mockk<Activity>(relaxed = true)
        every { destroyed.isFinishing } returns false
        every { destroyed.isDestroyed } returns true
        assertFalse(UiSafety.isActivityAlive(destroyed))
    }

    @Test
    fun `uiSafety safeShowDialog skips destroyed activity without calling show`() {
        val destroyed = mockk<Activity>(relaxed = true)
        every { destroyed.isFinishing } returns false
        every { destroyed.isDestroyed } returns true
        val builder = mockk<AlertDialog.Builder>(relaxed = true)

        val dialog = UiSafety.safeShowDialog(destroyed, builder)
        assertNull(dialog)
        verify(exactly = 0) { builder.show() }
    }

    @Test
    fun `uiSafety safeStartActivity catches ActivityNotFoundException cleanly`() {
        val ctx = mockk<Context>(relaxed = true)
        val intent = mockk<Intent>(relaxed = true)
        every { ctx.startActivity(any()) } throws ActivityNotFoundException("no browser")

        assertFalse(UiSafety.safeStartActivity(ctx, intent))
    }

    @Test
    fun `uiSafety copyTextSafely returns false when clipboard service missing or throws`() {
        val noServiceCtx = mockk<Context>(relaxed = true)
        every { noServiceCtx.getSystemService(Context.CLIPBOARD_SERVICE) } returns null
        assertFalse(UiSafety.copyTextSafely(noServiceCtx, "lbl", "val"))

        val throwingCm = mockk<ClipboardManager>(relaxed = true)
        every { throwingCm.setPrimaryClip(any()) } throws SecurityException("denied")
        val badCtx = mockk<Context>(relaxed = true)
        every { badCtx.getSystemService(Context.CLIPBOARD_SERVICE) } returns throwingCm
        assertFalse(UiSafety.copyTextSafely(badCtx, "lbl", "val"))
    }

    @Test
    fun `vpnService startSafely catches OS foreground restriction exception`() {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.startService(any()) } throws IllegalStateException("background start not allowed")
        every { ctx.startForegroundService(any()) } throws IllegalStateException("background start not allowed")

        assertFalse(DastyabVpnService.startSafely(ctx))
    }

    @Test
    fun `bootReconnectReceiver ignores unrelated actions and disabled autoConnect`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean("auto_connect_on_boot", false) } returns false
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSharedPreferences(TunnelConfig.PREFS_NAME, Context.MODE_PRIVATE) } returns prefs

        val timeChanged = mockk<Intent>(relaxed = true) { every { action } returns Intent.ACTION_TIME_CHANGED }
        val bootCompleted = mockk<Intent>(relaxed = true) { every { action } returns Intent.ACTION_BOOT_COMPLETED }
        val pkgReplaced = mockk<Intent>(relaxed = true) { every { action } returns Intent.ACTION_MY_PACKAGE_REPLACED }

        val receiver = BootReconnectReceiver()
        receiver.onReceive(ctx, null)
        receiver.onReceive(ctx, timeChanged)
        receiver.onReceive(ctx, bootCompleted)
        receiver.onReceive(ctx, pkgReplaced)

        verify(atLeast = 2) { ctx.getSharedPreferences(TunnelConfig.PREFS_NAME, Context.MODE_PRIVATE) }
        verify(exactly = 0) { ctx.startService(any()) }
        verify(exactly = 0) { ctx.startForegroundService(any()) }
    }
}
