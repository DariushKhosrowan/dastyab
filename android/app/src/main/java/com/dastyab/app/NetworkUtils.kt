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
}