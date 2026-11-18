package com.dastyab.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

/**
 * BootReconnectReceiver — Opt-in Auto-Reconnect on Device Boot or App Upgrade.
 *
 * When enabled in Tunnel Settings (`autoConnectOnBoot == true`) and VPN consent has already
 * been granted by the user (`VpnService.prepare(context) == null`), safely attempts to start
 * `DastyabVpnService`. If the OS blocks background foreground-service launch (Android 14/15+),
 * posts a user-tappable notification instead of crashing.
 */
class BootReconnectReceiver : BroadcastReceiver() {

    companion object {
        private const val RECONNECT_CHANNEL_ID = "dastyab_vpn_reconnect"
        private const val RECONNECT_NOTIFICATION_ID = 1043
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (!UiSafety.isUserUnlockedCompat(context)) {
            DastyabLogger.w("BootReconnect", "User storage still locked; skipping boot auto-connect")
            return
        }
        val settings = try {
            TunnelConfig.load(context)
        } catch (t: Throwable) {
            DastyabLogger.w("BootReconnect", t, "Failed to read TunnelConfig during boot receiver")
            return
        }
        if (!settings.autoConnectOnBoot) return

        val prep = try {
            VpnService.prepare(context)
        } catch (_: Throwable) {
            null
        }
        if (prep == null) {
            DastyabLogger.i("BootReconnect", "Auto-reconnecting DastYab VPN after %s", action)
            val started = DastyabVpnService.startSafely(context)
            if (started) {
                DastyabWidgetProvider.updateAllWidgets(context)
            } else {
                postReconnectNotification(context)
            }
        } else {
            DastyabLogger.w("BootReconnect", "Skipping boot auto-connect: VPN consent not yet granted")
        }
    }

    private fun postReconnectNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RECONNECT_CHANNEL_ID,
                    "DastYab VPN Reconnect",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Prompts to reconnect DastYab VPN when background launch is restricted"
                }
                nm.createNotificationChannel(channel)
            }
            val openApp = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_AUTO_CONNECT_VPN, true)
            }
            val pending = PendingIntent.getActivity(
                context,
                RECONNECT_NOTIFICATION_ID,
                openApp,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, RECONNECT_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            val notification = builder
                .setSmallIcon(R.drawable.ic_notification_vpn)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText("Tap to reconnect DastYab VPN tunnel")
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            nm.notify(RECONNECT_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            DastyabLogger.w("BootReconnect", t, "Could not post reconnect notification")
        }
    }
}
