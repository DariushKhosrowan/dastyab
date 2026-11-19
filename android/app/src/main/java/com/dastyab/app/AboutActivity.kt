package com.dastyab.app

import android.os.Bundle
import android.widget.TextView

/**
 * AboutActivity — shows app name, version, description, and a list of
 * available dastyab commands with descriptions.
 */
class AboutActivity : DastyabActivity() {

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
