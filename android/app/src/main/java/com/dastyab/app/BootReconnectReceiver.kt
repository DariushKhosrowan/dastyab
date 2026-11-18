package com.dastyab.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * BootReconnectReceiver — Opt-in Auto-Reconnect on Device Boot.
 *
 * When enabled in Tunnel Settings (`autoConnectOnBoot == true`) and VPN consent has already
 * been granted by the user (`VpnService.prepare(context) == null`), automatically starts
 * `DastyabVpnService` after device reboot.
 */
class BootReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val settings = TunnelConfig.load(context)
        if (!settings.autoConnectOnBoot) return

        val prep = VpnService.prepare(context)
        if (prep == null) {
            DastyabLogger.i("BootReconnect", "Auto-reconnecting DastYab VPN after device boot")
            DastyabVpnService.start(context)
            DastyabWidgetProvider.updateAllWidgets(context)
        } else {
            DastyabLogger.w("BootReconnect", "Skipping boot auto-connect: VPN consent not yet granted")
        }
    }
}
