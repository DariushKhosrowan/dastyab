package com.dastyab.app

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * BypassStateStore — Global state tracking for successful Brutal Lion anti-geoblock bypasses.
 *
 * Ensures that when the InAppBrowser or ReachabilityVerifier successfully bypasses regional
 * restrictions for AI Studio or Google Flow, the entire app (MainActivity, status badges, hero banner)
 * instantly reflects the successful bypass state.
 */
object BypassStateStore {

    const val ACTION_BYPASS_SUCCESSFUL = "com.dastyab.app.ACTION_BYPASS_SUCCESSFUL"
    const val EXTRA_SERVICE = "extra_bypass_service"
    const val EXTRA_ROUTE = "extra_bypass_route"

    private val bypassed = AtomicBoolean(false)
    private val serviceRef = AtomicReference("AI Studio & Flow")
    private val routeRef = AtomicReference("Direct / Brutal Chain")
    private val timestampAtomic = AtomicLong(0L)

    data class State(
        val isBypassed: Boolean,
        val service: String,
        val route: String,
        val timestamp: Long
    )

    fun markBypassed(context: Context, service: String, route: String) {
        bypassed.set(true)
        serviceRef.set(service)
        routeRef.set(route)
        timestampAtomic.set(System.currentTimeMillis())

        val intent = Intent(ACTION_BYPASS_SUCCESSFUL).apply {
            putExtra(EXTRA_SERVICE, service)
            putExtra(EXTRA_ROUTE, route)
            package = context.packageName
        }
        runCatching { context.sendBroadcast(intent) }
    }

    fun getSnapshot(): State {
        return State(
            isBypassed = bypassed.get(),
            service = serviceRef.get(),
            route = routeRef.get(),
            timestamp = timestampAtomic.get()
        )
    }

    fun reset() {
        bypassed.set(false)
        serviceRef.set("AI Studio & Flow")
        routeRef.set("")
        timestampAtomic.set(0L)
    }
}
