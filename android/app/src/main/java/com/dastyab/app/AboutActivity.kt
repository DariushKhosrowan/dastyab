package com.dastyab.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * AboutActivity — shows app name, version, description, and a list of
 * available dastyab commands with descriptions.
 */
class AboutActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        DastyabLogger.i("AboutActivity", "onCreate")

        val tvVersion = findViewById<TextView>(R.id.tvAboutVersion)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        tvVersion.text = getString(R.string.about_version_label) + ": " + versionName
    }
}
