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
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, DastyabWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                updateWidget(context, mgr, id)
            }
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val localized = LocaleHelper.wrapContext(context)
            val views = RemoteViews(context.packageName, R.layout.widget_dastyab_vpn)
            val status = DastyabVpnService.getStatus()
            val layer = VpnLayerStore.getActiveLayer(context)

            when (status.state) {
                DastyabVpnService.State.CONNECTED -> {
                    views.setImageViewResource(R.id.widgetStatusDot, R.drawable.status_dot_green)
                    views.setTextViewText(R.id.widgetTitle, localized.getString(R.string.vpn_status_connected))
                    views.setTextViewText(R.id.widgetSubtitle, "${layer.name} · ${status.mode.label}")
                    views.setTextViewText(R.id.widgetBtnToggle, localized.getString(R.string.vpn_btn_disconnect))
                }
                DastyabVpnService.State.CONNECTING -> {
                    views.setImageViewResource(R.id.widgetStatusDot, R.drawable.status_dot_orange)
                    views.setTextViewText(R.id.widgetTitle, localized.getString(R.string.vpn_status_connecting))
                    views.setTextViewText(R.id.widgetSubtitle, layer.name)
                    views.setTextViewText(R.id.widgetBtnToggle, localized.getString(R.string.vpn_btn_disconnect))
                }
                DastyabVpnService.State.DISCONNECTED -> {
                    views.setImageViewResource(R.id.widgetStatusDot, R.drawable.status_dot_gray)
                    views.setTextViewText(R.id.widgetTitle, localized.getString(R.string.vpn_status_disconnected))
                    views.setTextViewText(R.id.widgetSubtitle, layer.name)
                    views.setTextViewText(R.id.widgetBtnToggle, localized.getString(R.string.vpn_btn_connect))
                }
            }

            val toggleIntent = Intent(context, DastyabWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_TOGGLE
            }
            val togglePending = PendingIntent.getBroadcast(
                context,
                widgetId,
                toggleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
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
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_TOGGLE -> {
                if (DastyabVpnService.isConnected()) {
                    DastyabVpnService.stop(context)
                } else {
                    val prep = VpnService.prepare(context)
                    if (prep == null) {
                        DastyabVpnService.start(context)
                    } else {
                        // First-time VPN consent required: open MainActivity to trigger consent dialog
                        val launch = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(MainActivity.EXTRA_AUTO_CONNECT_VPN, true)
                        }
                        context.startActivity(launch)
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
