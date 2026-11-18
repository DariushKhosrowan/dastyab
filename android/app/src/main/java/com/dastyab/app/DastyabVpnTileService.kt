package com.dastyab.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * DastyabVpnTileService — Android Quick Settings Tile (API 24+) giving 1-tap
 * accessibility to toggle DastYab's active VPN Layer right from the system shade.
 */
class DastyabVpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (DastyabVpnService.isConnected()) {
            DastyabVpnService.stop(this)
            refreshTile(forceDisconnected = true)
            return
        }

        // If system VPN consent is already granted, start immediately without leaving the shade.
        val prep = VpnService.prepare(this)
        if (prep == null) {
            DastyabVpnService.start(this)
            refreshTile(forceConnected = true)
        } else {
            // Consent needed: open MainActivity so Android can present the VPN consent dialog.
            val openApp = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    openApp,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(openApp)
            }
        }
    }

    private fun refreshTile(forceConnected: Boolean = false, forceDisconnected: Boolean = false) {
        val tile = qsTile ?: return
        val connected = when {
            forceConnected -> true
            forceDisconnected -> false
            else -> DastyabVpnService.isConnected()
        }
        val activeLayer = VpnLayerStore.getActiveLayer(this)
        tile.label = "DastYab VPN"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (connected) activeLayer.mode.label else "Tap to connect"
        }
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
