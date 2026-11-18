package com.dastyab.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashHandler — installs a global uncaught-exception handler so that every
 * crash on a real device is captured to a log file before the process dies.
 *
 * Uses non-blocking logger writes and writes a pending-crash marker so the next
 * launch can surface a calm Safe-Mode recovery prompt without deadlocking.
 */
object CrashHandler {

    private const val CRASH_DIR_NAME = "crashes"
    private const val CRASH_FILE_BASE = "crash"
    private const val CRASH_FILE_EXT = ".log"
    private const val PENDING_MARKER = "last_crash.marker"

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
        try {
            try {
                handleCrash(thread, throwable)
            } catch (_: Throwable) {
                // Crash reporting must never prevent Android's termination handler.
            }
        } finally {
            originalHandler?.uncaughtException(thread, throwable)
        }
    }
    @Volatile
    private var crashDir: File? = null

    @Synchronized
    fun install(ctx: Context, filesDir: File) {
        val dir = File(filesDir, CRASH_DIR_NAME)
        if (!dir.exists()) { dir.mkdirs() }
        crashDir = dir

        if (Thread.getDefaultUncaughtExceptionHandler() !== handler) {
            originalHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        DastyabLogger.i("CrashHandler", "Crash handler installed. Dir: %s", dir.absolutePath)
    }

    private fun handleCrash(thread: Thread, throwable: Throwable) {
        val now = Date()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(now)
        val sessionId = DastyabLogger.getSessionId()

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val sb = StringBuilder()
        sb.append("==================== CRASH REPORT ====================")
        sb.append("\nTimestamp: ").append(timestamp)
        sb.append("\nSession:   ").append(sessionId)
        sb.append("\nThread:    ").append(thread.name)
        sb.append("\nModel:     ").append(Build.MODEL)
        sb.append("\nSDK:       ").append(Build.VERSION.SDK_INT)
        sb.append("\nABI:       ").append(Build.SUPPORTED_ABIS?.firstOrNull() ?: Build.CPU_ABI ?: "unknown")
        sb.append("\nApp:       com.dastyab.app")
        sb.append("\nThrowable: ").append(throwable::class.java.name)
        sb.append("\nMessage:   ").append(throwable.message ?: "no message")
        sb.append("\n---------------- Stack Trace ----------------")
        sb.append("\n").append(stackTrace)
        sb.append("\n---------------- Threads ----------------")
        sb.append("\n").append(threadDump())
        sb.append("\n=============================================")

        val dir = crashDir
        if (dir != null) {
            val crashFile = File(dir, "$CRASH_FILE_BASE${System.currentTimeMillis()}$CRASH_FILE_EXT")
            try {
                FileWriter(crashFile).use { it.write(sb.toString()) }
                File(dir, PENDING_MARKER).writeText("${throwable::class.java.simpleName}: ${throwable.message ?: ""}")
            } catch (e: Exception) {
                try { Log.e("CrashHandler", "Failed to write crash file", e) } catch (_: Throwable) {}
            }
        }

        // Non-blocking log so CrashHandler can never deadlock on a held logger lock.
        DastyabLogger.eNonBlocking("CrashHandler", throwable, "FATAL CRASH on thread %s", thread.name)
    }

    private fun threadDump(): String {
        val sb = StringBuilder()
        val allTraces = Thread.getAllStackTraces()
        for ((t, stack) in allTraces) {
            sb.append("\n-- ").append(t.name).append(" (").append(t.id).append(") --")
            for (ste in stack) {
                sb.append("\n    at ").append(ste)
            }
        }
        return sb.toString()
    }

    fun getCrashFiles(): List<File> {
        val dir = crashDir ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith(CRASH_FILE_BASE) && f.name.endsWith(CRASH_FILE_EXT) }
            ?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    fun consumePendingCrashNotice(): String? {
        val dir = crashDir ?: return null
        val marker = File(dir, PENDING_MARKER)
        if (!marker.isFile) return null
        return try {
            val summary = marker.readText().trim().ifEmpty { "Previous session exited unexpectedly." }
            marker.delete()
            summary
        } catch (_: Throwable) {
            null
        }
    }

    /** Delete all crash files and markers. @return number of crash log files deleted. */
    fun clearCrashFiles(): Int {
        val dir = crashDir
        if (dir != null) {
            runCatching { File(dir, PENDING_MARKER).delete() }
        }
        var deleted = 0
        for (f in getCrashFiles()) {
            if (f.delete()) deleted++
        }
        return deleted
    }
}