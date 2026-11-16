package com.dastyab.app

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * LogViewerActivity — lets users view the app's rolling log files directly on
 * the device, without needing adb. Essential for diagnosing issues on user
 * devices where Logcat is not accessible.
 *
 * Shows a list of log files (newest first); tapping one displays its contents
 * in the bottom pane. Includes a Refresh button to re-scan for new files.
 */
class LogViewerActivity : Activity() {

    private lateinit var listLogs: ListView
    private lateinit var tvLogContent: TextView
    private lateinit var btnRefresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        listLogs = findViewById(R.id.listLogs)
        tvLogContent = findViewById(R.id.tvLogContent)
        btnRefresh = findViewById(R.id.btnRefresh)

        DastyabLogger.i("LogViewerActivity", "onCreate")

        refreshFileList()

        btnRefresh.setOnClickListener {
            refreshFileList()
            DastyabLogger.d("LogViewerActivity", "Refresh requested by user")
        }

        listLogs.setOnItemClickListener { _, _, position, _ ->
            val files = DastyabLogger.getLogFiles()
            if (position < files.size) {
                val file = files[position]
                tvLogContent.text = file.readText()
                DastyabLogger.i("LogViewerActivity", "Viewed log file: %s", file.name)
            }
        }
    }

    private fun refreshFileList() {
        val files = DastyabLogger.getLogFiles()
        val names = files.map { it.name }.toTypedArray()
        val adapter = ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, names
        )
        listLogs.adapter = adapter

        if (files.isNotEmpty()) {
            tvLogContent.text = files[0].readText()
        } else {
            tvLogContent.text = getString(R.string.no_logs_available)
        }
    }

    override fun onDestroy() {
        DastyabLogger.i("LogViewerActivity", "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "LogViewerActivity"
    }
}