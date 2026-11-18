package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.UserManager

/**
 * UiSafety — Centralized lifecycle, Direct Boot, dialog, and activity-launch guards
 * that prevent BadTokenException, WindowLeaked, IllegalStateException, and ActivityNotFoundException.
 */
object UiSafety {

    fun isActivityAlive(activity: Activity?): Boolean {
        if (activity == null) return false
        return !activity.isFinishing && !activity.isDestroyed
    }

    fun safePostUi(activity: Activity, handler: Handler, action: () -> Unit) {
        if (!isActivityAlive(activity)) return
        handler.post {
            if (isActivityAlive(activity)) {
                try {
                    action()
                } catch (t: Throwable) {
                    DastyabLogger.e("UiSafety", t, "Suppressed UI callback failure")
                }
            }
        }
    }

    fun safeShowDialog(context: Context, builder: AlertDialog.Builder): AlertDialog? {
        if (context is Activity && !isActivityAlive(context)) {
            return null
        }
        return try {
            builder.show()
        } catch (t: Throwable) {
            DastyabLogger.w("UiSafety", t, "Unable to show dialog safely")
            null
        }
    }

    fun safeStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            DastyabLogger.w("UiSafety", t, "Failed to start activity %s", intent.component?.className ?: intent.action)
            false
        }
    }

    fun copyTextSafely(context: Context, label: String, text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (t: Throwable) {
            DastyabLogger.w("UiSafety", t, "Clipboard write failed")
            false
        }
    }

    /**
     * Returns false only when the device is in Direct Boot locked state where
     * Credential-Encrypted SharedPreferences are inaccessible.
     */
    fun isUserUnlockedCompat(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return try {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            um?.isUserUnlocked ?: true
        } catch (_: Throwable) {
            true
        }
    }
}
