package com.dastyab.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock

object DastyabLogger {

    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_BASE = "dastyab"
    private const val LOG_FILE_EXT = ".log"
    private const val MAX_LOG_FILES = 5
    private const val MAX_LOG_FILE_SIZE = 2 * 1024 * 1024L

    private var minFileLevel: LogLevel = LogLevel.DEBUG
    private val tagLevels = mutableMapOf<String, LogLevel>()
    private var logDir: File? = null
    private var context: Context? = null
    private var sessionId: String = UUID.randomUUID().toString().substring(0, 8)
    private val lock = ReentrantLock()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private var appVersion: String? = null
    private var deviceModel: String? = null
    private var sdkInt: Int? = null
    private var abi: String? = null

    fun init(filesDir: File, ctx: Context? = null) {
        lock.lock()
        try {
            context = ctx
            logDir = File(filesDir, LOG_DIR_NAME)
            if (!logDir!!.exists()) {
                if (!logDir!!.mkdirs()) {
                    Log.w("DastyabLogger", "Could not create log dir: ${logDir!!.absolutePath}")
                }
            }
            captureDeviceInfo(ctx)
            appVersion = ctx?.packageName?.let {
                try {
                    ctx.packageManager.getPackageInfo(it, 0).versionName
                } catch (_: Exception) { null }
            }

            i("DastyabLogger",
                "Logger initialized — session=" + sessionId +
                    ", dir=" + logDir!!.absolutePath +
                    ", model=" + deviceModel +
                    ", sdk=" + sdkInt +
                    ", abi=" + abi +
                    ", appVersion=" + appVersion)
        } finally {
            lock.unlock()
        }
    }

    private fun captureDeviceInfo(ctx: Context?) {
        deviceModel = Build.MODEL
        sdkInt = Build.VERSION.SDK_INT
        abi = try {
            Build.SUPPORTED_ABIS?.firstOrNull() ?: Build.CPU_ABI
        } catch (_: Throwable) { null }
    }

    fun setMinFileLevel(level: LogLevel) {
        lock.lock()
        try { minFileLevel = level } finally { lock.unlock() }
    }

    fun getCurrentMinFileLevel(): LogLevel {
        lock.lock()
        try { return minFileLevel } finally { lock.unlock() }
    }

    fun setTagLevel(tag: String, level: LogLevel) {
        lock.lock()
        try { tagLevels[tag] = level } finally { lock.unlock() }
    }

    /** Reset min file level and per-tag levels to defaults (DEBUG / none). */
    fun resetLevels() {
        lock.lock()
        try {
            minFileLevel = LogLevel.DEBUG
            tagLevels.clear()
        } finally { lock.unlock() }
    }

    /** Shutdown the logger — clears logDir so no stale file handles remain.
     *  Useful for test isolation and clean process teardown. */
    fun shutdown() {
        lock.lock()
        try {
            logDir = null
            context = null
            appVersion = null
            deviceModel = null
            sdkInt = null
            abi = null
        } finally { lock.unlock() }
    }

    private fun levelForTag(tag: String): LogLevel = tagLevels[tag] ?: minFileLevel

    fun v(tag: String, message: String, vararg args: Any?) = log(LogLevel.VERBOSE, tag, format(message, args))
    fun d(tag: String, message: String, vararg args: Any?) = log(LogLevel.DEBUG, tag, format(message, args))
    fun i(tag: String, message: String, vararg args: Any?) = log(LogLevel.INFO, tag, format(message, args))
    fun w(tag: String, message: String, vararg args: Any?) = log(LogLevel.WARN, tag, format(message, args))
    fun e(tag: String, message: String, vararg args: Any?) = log(LogLevel.ERROR, tag, format(message, args))
    fun wtf(tag: String, message: String, vararg args: Any?) = log(LogLevel.ASSERT, tag, format(message, args))

    fun e(tag: String, throwable: Throwable, message: String, vararg args: Any?) {
        val formatted = format(message, args) + "\n" + formatThrowable(throwable)
        log(LogLevel.ERROR, tag, formatted)
    }

    fun w(tag: String, throwable: Throwable, message: String, vararg args: Any?) {
        val formatted = format(message, args) + "\n" + formatThrowable(throwable)
        log(LogLevel.WARN, tag, formatted)
    }

    /** Render a throwable's stack trace without depending on android.util.Log
     *  (which is unavailable in plain-JVM unit tests). */
    private fun formatThrowable(t: Throwable): String = try {
        t.stackTraceToString()
    } catch (_: Throwable) {
        t.toString()
    }

    fun getSessionId(): String = sessionId
    fun getLogDir(): File? = logDir

    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f ->
            f.name.startsWith(LOG_FILE_BASE) && f.name.endsWith(LOG_FILE_EXT)
        }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    fun getLatestLog(): String? {
        val files = getLogFiles()
        return if (files.isEmpty()) null else files[0].readText()
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        if (level.priority < levelForTag(tag).priority) return
        val now = Date()
        val thread = Thread.currentThread().name

        try { Log.println(level.logcat, tag, message) } catch (_: Exception) {}

        lock.lock()
        try {
            val timestamp = dateFormat.format(now)
            val entry = LogEntry(
                timestampMs = now.time,
                timestampIso = timestamp,
                thread = thread,
                tag = tag,
                level = level.name,
                message = message,
                appVersion = appVersion,
                deviceModel = deviceModel,
                sdkInt = sdkInt,
                abi = abi,
                sessionId = sessionId
            )
            writeToFile(entry.toLogLine())
        } finally {
            lock.unlock()
        }
    }

    private fun writeToFile(line: String) {
        val dir = logDir ?: return
        val currentFile = currentLogFile(dir)
        try {
            FileWriter(currentFile, true).use { it.append(line); it.append("\n") }
        } catch (e: IOException) {
            Log.w("DastyabLogger", "Failed to write log file", e)
        }
    }

    private fun currentLogFile(dir: File): File {
        val files = dir.listFiles { f ->
            f.name.startsWith(LOG_FILE_BASE) && f.name.endsWith(LOG_FILE_EXT)
        } ?: return File(dir, "$LOG_FILE_BASE$LOG_FILE_EXT")

        val sorted = files.sortedByDescending { it.lastModified() }
        return if (sorted.isEmpty() || sorted[0].length() >= MAX_LOG_FILE_SIZE) {
            val nextIndex = files.size
            File(dir, "$LOG_FILE_BASE$nextIndex$LOG_FILE_EXT")
        } else {
            sorted[0]
        }
    }

    private fun format(template: String, args: Array<out Any?>): String {
        return if (args.isEmpty()) template
        else try { String.format(template, *args) } catch (_: Exception) { template }
    }

    enum class LogLevel(val priority: Int, val logcat: Int) {
        VERBOSE(0, Log.VERBOSE),
        DEBUG(1, Log.DEBUG),
        INFO(2, Log.INFO),
        WARN(3, Log.WARN),
        ERROR(4, Log.ERROR),
        ASSERT(5, Log.ASSERT);
    }
}