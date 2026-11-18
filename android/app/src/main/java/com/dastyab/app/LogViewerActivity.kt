package com.dastyab.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * LogViewerActivity — lets users view the app's rolling log files directly on
 * the device, without needing adb. Essential for diagnosing issues on user
 * devices where Logcat is not accessible.
 *
 * Reads log files asynchronously off the main thread (bounded to the trailing 256 KB)
 * so large crash/diagnostic logs never trigger ANR or OutOfMemoryError.
 */
class LogViewerActivity : Activity() {

    private lateinit var listLogs: ListView
    private lateinit var tvLogContent: TextView
    private lateinit var btnRefresh: Button
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var displayedFiles: List<File> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        listLogs = findViewById(R.id.listLogs)
        tvLogContent = findViewById(R.id.tvLogContent)
        btnRefresh = findViewById(R.id.btnRefresh)

        DastyabLogger.i(LOG_TAG, "onCreate")

        refreshFileList()

        btnRefresh.setOnClickListener {
            refreshFileList()
            DastyabLogger.d(LOG_TAG, "Refresh requested by user")
        }

        listLogs.setOnItemClickListener { _, _, position, _ ->
            val files = displayedFiles
            if (position in files.indices) {
                val file = files[position]
                loadFileContentAsync(file)
                DastyabLogger.i(LOG_TAG, "Viewed log file: %s", file.name)
            }
        }
    }

    private fun refreshFileList() {
        val logFiles = DastyabLogger.getLogFiles()
        val crashFiles = CrashHandler.getCrashFiles()
        val combined = (crashFiles + logFiles).sortedByDescending { it.lastModified() }
        displayedFiles = combined

        val names = combined.map { f ->
            if (f.name.startsWith("crash")) "[CRASH] ${f.name}" else f.name
        }.toTypedArray()
        listLogs.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)

        if (combined.isNotEmpty()) {
            loadFileContentAsync(combined[0])
        } else {
            tvLogContent.text = getString(R.string.no_logs_available)
        }
    }

    private fun loadFileContentAsync(file: File) {
        tvLogContent.text = "Loading ${file.name}…"
        thread(isDaemon = true, name = "dastyab-log-reader") {
            val content = readTailSafely(file, MAX_LOG_PREVIEW_BYTES)
            UiSafety.safePostUi(this, handler) {
                tvLogContent.text = content
            }
        }
    }

    private fun readTailSafely(file: File, maxBytes: Int): String {
        return try {
            if (!file.isFile) return "(Log file no longer exists)"
            val len = file.length()
            if (len <= maxBytes) {
                file.readText(Charsets.UTF_8)
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(len - maxBytes)
                    val buf = ByteArray(maxBytes)
                    val read = raf.read(buf)
                    val tail = if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
                    "... (showing last ${maxBytes / 1024} KB of ${len / 1024} KB) ...\n$tail"
                }
            }
        } catch (t: Throwable) {
            "Unable to read ${file.name}: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        DastyabLogger.i(LOG_TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "LogViewerActivity"
        private const val MAX_LOG_PREVIEW_BYTES = 256 * 1024
    }
}