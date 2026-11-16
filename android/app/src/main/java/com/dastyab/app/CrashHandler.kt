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
import java.util.UUID

/**
 * CrashHandler — installs a global uncaught-exception handler so that every
 * crash on a real device is captured to a log file before the process dies.
 *
 * Without this, crashes on user devices are invisible unless the user happens
 * to have adb connected. With this, every crash gets a timestamped stack trace
 * plus full device context written to <filesDir>/logs/crashes/.
 *
 * Usage (in Application.onCreate or Activity.onCreate):
 *   CrashHandler.install(context, filesDir)
 */
object CrashHandler {

    private const val CRASH_DIR_NAME = "crashes"
    private const val CRASH_FILE_BASE = "crash"
    private const val CRASH_FILE_EXT = ".log"

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var context: Context? = null
    private var crashDir: File? = null

    fun install(ctx: Context, filesDir: File) {
        context = ctx
        crashDir = File(filesDir, CRASH_DIR_NAME)
        if (!crashDir!!.exists()) { crashDir!!.mkdirs() }

        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)
            // Fall through to the original handler so the app still crashes
            // (Android expects unhandled exceptions to terminate the process).
            originalHandler?.uncaughtException(thread, throwable)
        }

        DastyabLogger.i("CrashHandler", "Crash handler installed. Dir: %s", crashDir!!.absolutePath)
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
        sb.append("\nABI:       ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI)
        sb.append("\nApp:       com.dastyab.app")
        sb.append("\nThrowable: ").append(throwable::class.java.name)
        sb.append("\nMessage:   ").append(throwable.message ?: "no message")
        sb.append("\n---------------- Stack Trace ----------------")
        sb.append("\n").append(stackTrace)
        sb.append("\n---------------- Threads ----------------")
        sb.append("\n").append(threadDump())
        sb.append("\n=============================================")

        // Write to crash file
        val crashFile = File(crashDir, "$CRASH_FILE_BASE${System.currentTimeMillis()}$CRASH_FILE_EXT")
        try {
            FileWriter(crashFile).use { it.write(sb.toString()) }
        } catch (e: Exception) {
            Log.e("CrashHandler", "Failed to write crash file", e)
        }

        // Also log via DastyabLogger so it appears in regular logs too
        DastyabLogger.e("CrashHandler", "FATAL CRASH on thread ${thread.name}", throwable)
    }

    private fun threadDump(): String {
        val sb = StringBuilder()
        val threads: Collection<Thread> = Thread.getAllStackTraces().keys
        for (t in threads) {
            sb.append("\n-- ").append(t.name).append(" (").append(t.id).append(") --")
            val stack = Thread.getAllStackTraces()[t]
            if (stack != null) {
                for (ste in stack) {
                    sb.append("\n    at ").append(ste)
                }
            }
        }
        return sb.toString()
    }

    fun getCrashFiles(): List<File> {
        val dir = crashDir ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith(CRASH_FILE_BASE) && f.name.endsWith(CRASH_FILE_EXT) }
            ?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }
}