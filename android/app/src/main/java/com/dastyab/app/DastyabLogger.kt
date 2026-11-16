package com.dastyab.app

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
    private val lock = ReentrantLock()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(filesDir: File) {
        lock.lock()
        try {
            logDir = File(filesDir, LOG_DIR_NAME)
            if (!logDir!!.exists()) { logDir!!.mkdirs() }
            d("DastyabLogger", "Logger initialized. Log dir: %s", logDir!!.absolutePath)
        } finally { lock.unlock() }
    }

    fun setMinFileLevel(level: LogLevel) {
        lock.lock()
        try { minFileLevel = level } finally { lock.unlock() }
    }

    fun setTagLevel(tag: String, level: LogLevel) {
        lock.lock()
        try { tagLevels[tag] = level } finally { lock.unlock() }
    }

    private fun levelForTag(tag: String): LogLevel = tagLevels[tag] ?: minFileLevel

    fun v(tag: String, message: String, vararg args: Any?) = log(LogLevel.VERBOSE, tag, format(message, args))
    fun d(tag: String, message: String, vararg args: Any?) = log(LogLevel.DEBUG, tag, format(message, args))
    fun i(tag: String, message: String, vararg args: Any?) = log(LogLevel.INFO, tag, format(message, args))
    fun w(tag: String, message: String, vararg args: Any?) = log(LogLevel.WARN, tag, format(message, args))
    fun e(tag: String, message: String, vararg args: Any?) = log(LogLevel.ERROR, tag, format(message, args))
    fun wtf(tag: String, message: String, vararg args: Any?) = log(LogLevel.ASSERT, tag, format(message, args))

    fun e(tag: String, throwable: Throwable, message: String, vararg args: Any?) {
        val formatted = format(message, args) + "\n" + Log.getStackTraceString(throwable)
        log(LogLevel.ERROR, tag, formatted)
    }

    private enum class LogLevel(val priority: Int, val logcat: Int) {
        VERBOSE(0, Log.VERBOSE), DEBUG(1, Log.DEBUG), INFO(2, Log.INFO),
        WARN(3, Log.WARN), ERROR(4, Log.ERROR), ASSERT(5, Log.ASSERT);
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        if (level.priority < levelForTag(tag).priority) return
        val timestamp = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        val line = "[%s] [%s] [%s] %s %s".format(timestamp, thread, tag, level.name, message)
        try { Log.println(level.logcat, tag, message) } catch (_: Exception) {}
        lock.lock()
        try { writeToFile(line) } finally { lock.unlock() }
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
        val files = dir.listFiles { f -> f.name.startsWith(LOG_FILE_BASE) && f.name.endsWith(LOG_FILE_EXT) }
            ?: return File(dir, "$LOG_FILE_BASE$LOG_FILE_EXT")
        val sorted = files.sortedByDescending { it.lastModified() }
        return if (sorted.isEmpty() || sorted[0].length() >= MAX_LOG_FILE_SIZE) {
            File(dir, "$LOG_FILE_BASE${files.size}$LOG_FILE_EXT")
        } else { sorted[0] }
    }

    private fun format(template: String, args: Array<out Any?>): String {
        return try { java.text.MessageFormat.format(template, args) } catch (_: Exception) { template }
    }
}