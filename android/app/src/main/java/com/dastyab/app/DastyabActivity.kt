package com.dastyab.app

import android.app.Activity
import android.content.Context
import android.os.Bundle

/**
 * DastyabActivity — Base activity for all DastYab screens.
 *
 * Enforces the safe two-phase theme & locale lifecycle across initial launch and Activity.recreate():
 *  1. [attachBaseContext] wraps the context with the active [LocaleHelper] language and RTL/LTR direction.
 *  2. [onCreate] applies the theme style resource BEFORE subclasses call [setContentView].
 *  3. [onPostCreate] defers window/system-bar chrome customization to the installed DecorView.
 */
abstract class DastyabActivity : Activity() {

    protected open fun initializeBeforeTheme() {}

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeBeforeTheme()
        ThemeManager.applyTheme(this)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        ThemeManager.scheduleSystemBars(this)
    }
}
