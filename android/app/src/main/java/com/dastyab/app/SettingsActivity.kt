package com.dastyab.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * SettingsActivity — app settings screen:
 *  • Log file level selector (VERBOSE..ERROR) via DastyabLogger.setMinFileLevel()
 *  • Auto-update binary toggle (checked on app start in MainActivity)
 *  • Clear logs / crash logs / command history buttons
 *  • Force re-download binary button
 *  • About button
 */
class SettingsActivity : Activity() {

    private lateinit var levelSpinner: Spinner
    private lateinit var cbAutoUpdate: CheckBox
    private lateinit var tvStatus: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        setContentView(R.layout.activity_settings)

        val tvCurrentTheme = findViewById<TextView>(R.id.tvCurrentTheme)
        val btnChooseTheme = findViewById<Button>(R.id.btnChooseTheme)
        val tvCurrentLanguage = findViewById<TextView>(R.id.tvCurrentLanguage)
        val btnChooseLanguage = findViewById<Button>(R.id.btnChooseLanguage)

        tvCurrentTheme.text = getString(
            R.string.settings_current_theme_format,
            ThemeManager.localizedName(this, ThemeManager.getSelectedTheme(this))
        )
        tvCurrentLanguage.text = getString(
            R.string.settings_current_lang_format,
            LocaleHelper.displayName(LocaleHelper.getLanguage(this))
        )

        btnChooseTheme.setOnClickListener {
            ThemeManager.showThemePickerDialog(this) {
                recreate()
            }
        }
        btnChooseLanguage.setOnClickListener {
            LocaleHelper.showLanguagePickerDialog(this) {
                DastyabWidgetProvider.updateAllWidgets(this)
                recreate()
            }
        }

        levelSpinner = findViewById(R.id.spinnerLogLevel)
        cbAutoUpdate = findViewById(R.id.cbAutoUpdate)
        tvStatus = findViewById(R.id.tvSettingsStatus)
        val btnClearLogs = findViewById<Button>(R.id.btnClearLogs)
        val btnClearCrashLogs = findViewById<Button>(R.id.btnClearCrashLogs)
        val btnClearHistory = findViewById<Button>(R.id.btnClearHistory)
        val btnForceRedownload = findViewById<Button>(R.id.btnForceRedownload)
        val btnAbout = findViewById<Button>(R.id.btnSettingsAbout)

        // ── Log level spinner ──
        val levels = listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, levels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        levelSpinner.adapter = adapter

        val current = try {
            DastyabLogger.getCurrentMinFileLevel().name
        } catch (_: Throwable) { "DEBUG" }
        levelSpinner.setSelection(levels.indexOf(current).coerceAtLeast(0))

        var initialSelection = true
        levelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initialSelection) {
                    initialSelection = false
                    return
                }
                val name = levels[position]
                val level = try {
                    DastyabLogger.LogLevel.valueOf(name)
                } catch (_: Throwable) { return }
                DastyabLogger.setMinFileLevel(level)
                persistPref(PREF_LOG_LEVEL, name)
                DastyabLogger.i("Settings", "Log file level set to %s", name)
                setStatus(getString(R.string.settings_status_log_level, name))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── Auto-update toggle ──
        cbAutoUpdate.visibility = View.GONE

        // ── Clear logs ──
        btnClearLogs.setOnClickListener {
            val files = DastyabLogger.getLogFiles()
            var n = 0
            for (f in files) if (f.delete()) n++
            DastyabLogger.i("Settings", "Cleared %d log files", n)
            val msg = getString(R.string.settings_deleted_logs, n)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            setStatus(msg)
        }

        // ── Clear crash logs ──
        btnClearCrashLogs.setOnClickListener {
            val n = CrashHandler.clearCrashFiles()
            DastyabLogger.i("Settings", "Cleared %d crash files", n)
            val msg = getString(R.string.settings_deleted_crash_logs, n)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            setStatus(msg)
        }

        // ── Clear command history ──
        btnClearHistory.setOnClickListener {
            CommandHistory.clear()
            val msg = getString(R.string.settings_history_cleared)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            setStatus(msg)
        }

        // ── Force re-download binary ──
        btnForceRedownload.setOnClickListener {
            val opened = UiSafety.safeStartActivity(
                this,
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/DastYab/dastyab/releases"))
            )
            if (!opened) {
                setStatus(getString(R.string.settings_releases_hint))
            }
        }

        // ── About ──
        btnAbout.setOnClickListener {
            UiSafety.safeStartActivity(this, Intent(this, AboutActivity::class.java))
        }
    }

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun persistPref(key: String, value: String) {
        getPrefs().edit().putString(key, value).apply()
    }

    private fun persistPref(key: String, value: Boolean) {
        getPrefs().edit().putBoolean(key, value).apply()
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
    }

    companion object {
        const val PREFS_NAME = "dastyab_settings"
        const val PREF_LOG_LEVEL = "log_level"
        const val PREF_AUTO_UPDATE = "auto_update"
    }
}
