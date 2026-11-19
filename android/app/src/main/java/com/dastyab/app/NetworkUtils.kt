package com.dastyab.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * NetworkUtils — connectivity checks for the Dastyab Android app.
 *
 * Ensures we don't attempt a binary download when there's no network,
 * and surfaces a clear error instead of a cryptic timeout.
 */
object NetworkUtils {

    /**
     * Returns true if the device has an active internet connection
     * (Wi-Fi or mobile) that can reach the internet.
     */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                else -> false
            }
        } else {
            val net = cm.activeNetworkInfo
            return net != null && net.isConnected
        }
    }

    /**
     * Returns true when another VPN app (e.g. Lantern, v2rayNG VPN, Orbot VPN) currently owns
     * the Android system `VpnService` transport while DastYab's own VPN service is off.
     */
    fun hasActiveExternalVpn(context: Context): Boolean {
        if (DastyabVpnService.isConnected()) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } else {
            @Suppress("DEPRECATION")
            cm.getNetworkInfo(ConnectivityManager.TYPE_VPN)?.isConnected == true
        }
    }
}