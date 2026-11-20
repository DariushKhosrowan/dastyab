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
        try {
            if (DastyabVpnService.isConnected()) {
                DastyabVpnService.stop(this)
                refreshTile(forceDisconnected = true)
                return
            }

            // If system VPN consent is already granted, start immediately without leaving the shade.
            val prep = VpnService.prepare(this)
            if (prep == null && DastyabVpnService.startSafely(this)) {
                refreshTile(forceConnected = true)
            } else {
                // Consent needed (or OS blocked background FGS): open MainActivity cleanly.
                val openApp = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_AUTO_CONNECT_VPN, true)
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
        } catch (t: Throwable) {
            DastyabLogger.w("VpnTile", t, "Tile click handling failed")
        }
    }

    private fun refreshTile(forceConnected: Boolean = false, forceDisconnected: Boolean = false) {
        try {
            val tile = qsTile ?: return
            val localized = LocaleHelper.wrapContext(this)
            val connected = when {
                forceConnected -> true
                forceDisconnected -> false
                else -> DastyabVpnService.isConnected()
            }
            val activeLayer = if (UiSafety.isUserUnlockedCompat(this)) {
                runCatching { VpnLayerStore.getActiveLayer(this) }.getOrNull()
            } else {
                null
            }
            val watchdog = FailoverWatchdog.getSnapshot()
            val standbyCount = if (UiSafety.isUserUnlockedCompat(this)) {
                runCatching { AutopilotBrain.loadStandbyUris(this).size }.getOrDefault(0)
            } else {
                0
            }
            tile.label = localized.getString(R.string.tile_vpn_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (connected) {
                    val modeLabel = activeLayer?.mode?.localizedLabel(localized) ?: localized.getString(R.string.tile_connected)
                    val latencyTag = if (watchdog.lastLatencyMs > 0L) " · ${watchdog.lastLatencyMs}ms" else ""
                    "$modeLabel$latencyTag"
                } else if (standbyCount > 0) {
                    localized.getString(R.string.tile_tap_standby_ready, standbyCount)
                } else {
                    localized.getString(R.string.tile_tap_to_connect)
                }
            }
            tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        } catch (t: Throwable) {
            DastyabLogger.w("VpnTile", t, "Failed to update QS tile")
        }
    }
}
