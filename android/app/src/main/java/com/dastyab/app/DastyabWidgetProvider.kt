package com.dastyab.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews

/**
 * DastyabWidgetProvider — Royal Gold on Obsidian Home Screen VPN Widget.
 *
 * Displays live VPN connection state + active layer name, and provides a 1-tap
 * Connect / Disconnect button right from the Android home screen.
 */
class DastyabWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.dastyab.app.widget.TOGGLE_VPN"

        fun updateAllWidgets(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(context, DastyabWidgetProvider::class.java))
                if (ids.isEmpty()) return
                for (id in ids) {
                    updateWidget(context, mgr, id)
                }
            } catch (t: Throwable) {
                DastyabLogger.w("Widget", t, "Failed to refresh app widgets")
            }
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val localized = LocaleHelper.wrapContext(context)
            val views = RemoteViews(context.packageName, R.layout.widget_dastyab_vpn)
            val status = DastyabVpnService.getStatus()
            val layer = if (UiSafety.isUserUnlockedCompat(context)) {
                runCatching { VpnLayerStore.getActiveLayer(context) }.getOrNull()
            } else {
                null
            }
            val layerName = layer?.name ?: "Smart Tunnel"

            when (status.state) {
                DastyabVpnService.State.CONNECTED -> {
                    views.setImageViewResource(R.id.widgetStatusDot, R.drawable.status_dot_green)
                    views.setTextViewText(R.id.widgetTitle, localized.getString(R.string.vpn_status_connected))
                    views.setTextViewText(R.id.widgetSubtitle, "$layerName · ${status.mode.label}")
                    views.setTextViewText(R.id.widgetBtnToggle, localized.getString(R.string.vpn_btn_disconnect))
                }
                DastyabVpnService.State.CONNECTING -> {
                    views.setImageViewResource(R.id.widgetStatusDot, R.drawable.status_dot_orange)
                    views.setTextViewText(R.id.widgetTitle, localized.getString(R.string.vpn_status_connecting))
                    views.setTextViewText(R.id.widgetSubtitle, layerName)
                    views.setTextViewText(R.id.widgetBtnToggle, localized.getString(R.string.vpn_btn_disconnect))
                }
                DastyabVpnService.State.DISCONNECTED -> {
                    views.setImageViewResource(R.id.widgetStatusDot, R.drawable.status_dot_gray)
                    views.setTextViewText(R.id.widgetTitle, localized.getString(R.string.vpn_status_disconnected))
                    views.setTextViewText(R.id.widgetSubtitle, layerName)
                    views.setTextViewText(R.id.widgetBtnToggle, localized.getString(R.string.vpn_btn_connect))
                }
            }

            // Avoid Android 12–15 BAL trampoline: when VPN consent is not yet granted, bind the
            // toggle button directly to an Activity PendingIntent rather than rebroadcasting.
            val needsConsent = try {
                !DastyabVpnService.isConnected() && VpnService.prepare(context) != null
            } catch (_: Throwable) {
                false
            }
            val togglePending = if (needsConsent) {
                val consentIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_AUTO_CONNECT_VPN, true)
                }
                PendingIntent.getActivity(
                    context,
                    widgetId + 2000,
                    consentIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                val toggleIntent = Intent(context, DastyabWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_TOGGLE
                }
                PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    toggleIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            views.setOnClickPendingIntent(R.id.widgetBtnToggle, togglePending)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                widgetId + 1000,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openPending)

            mgr.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            runCatching { updateWidget(context, appWidgetManager, id) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_TOGGLE -> {
                if (DastyabVpnService.isConnected()) {
                    DastyabVpnService.stop(context)
                } else {
                    val prep = try { VpnService.prepare(context) } catch (_: Throwable) { null }
                    if (prep == null) {
                        if (!DastyabVpnService.startSafely(context)) {
                            val launch = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(MainActivity.EXTRA_AUTO_CONNECT_VPN, true)
                            }
                            UiSafety.safeStartActivity(context, launch)
                        }
                    } else {
                        val launch = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(MainActivity.EXTRA_AUTO_CONNECT_VPN, true)
                        }
                        UiSafety.safeStartActivity(context, launch)
                    }
                }
                updateAllWidgets(context)
            }
            DastyabVpnService.ACTION_STATE_CHANGED -> {
                updateAllWidgets(context)
            }
        }
    }
}
