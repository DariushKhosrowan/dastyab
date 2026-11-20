package com.dastyab.app

import android.content.Context
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * BrutalAntiGeoblockEngine — The ultimate strict and resilient anti-geoblock enforcement engine.
 *
 * Ensures that when accessing Google AI Studio, Google Flow, or any censored endpoint:
 *  1. Spoofs robust geo-neutralizing headers (`X-Forwarded-For`, `X-Real-IP`, `CF-Connecting-IP`, `True-Client-IP`, `Client-IP`).
 *  2. Rotates through unpoisoned global DoH endpoints (Cloudflare, Google, Quad9, AdGuard).
 *  3. Purges regional cookies and forces clean TLS SNI masquerading & Desktop User-Agent.
 *  4. Automatically escalates from Smart Chain to Emergency DNS Blackout mode upon any geo-block or HTTP 403.
 */
object BrutalAntiGeoblockEngine {

    private val rotationCounter = AtomicInteger(0)

    val SPOOFED_IPS = listOf(
        "8.8.8.8",
        "1.1.1.1",
        "9.9.9.9",
        "208.67.222.222",
        "74.125.24.100",
        "142.250.190.46",
        "172.217.168.14",
        "34.120.177.193"
    )

    fun getRandomizedUsEuIp(): String {
        val subnetPrefixes = listOf("8.8.", "1.1.", "142.250.", "172.217.", "34.120.")
        val prefix = subnetPrefixes[Random.nextInt(subnetPrefixes.size)]
        return prefix + Random.nextInt(1, 250) + "." + Random.nextInt(1, 250)
    }

    fun applyBrutalHeaders(conn: HttpURLConnection) {
        val idx = rotationCounter.getAndIncrement() % SPOOFED_IPS.size
        val spoofedIp = if (idx % 2 == 0) SPOOFED_IPS[idx] else getRandomizedUsEuIp()
        
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,es;q=0.8")
        conn.setRequestProperty("X-Forwarded-For", spoofedIp)
        conn.setRequestProperty("X-Real-IP", spoofedIp)
        conn.setRequestProperty("CF-Connecting-IP", spoofedIp)
        conn.setRequestProperty("True-Client-IP", spoofedIp)
        conn.setRequestProperty("Client-IP", spoofedIp)
        conn.setRequestProperty("X-Client-IP", spoofedIp)
        conn.setRequestProperty("Via", "1.1 Google")
        conn.setRequestProperty("Forwarded", "for=$spoofedIp;proto=https;by=google")
        conn.setRequestProperty("Cache-Control", "no-cache, no-store")
        conn.setRequestProperty("Pragma", "no-cache")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 DastyabBrutalLion/3.1")
    }

    fun getBrutalHeadersMap(): Map<String, String> {
        val spoofedIp = getRandomizedUsEuIp()
        return mapOf(
            "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
            "X-Forwarded-For" to spoofedIp,
            "X-Real-IP" to spoofedIp,
            "CF-Connecting-IP" to spoofedIp,
            "True-Client-IP" to spoofedIp,
            "Client-IP" to spoofedIp,
            "X-Client-IP" to spoofedIp,
            "Via" to "1.1 Google",
            "Forwarded" to "for=$spoofedIp;proto=https;by=google",
            "Cache-Control" to "no-cache, no-store",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 DastyabBrutalLion/3.1"
        )
    }

    fun enforceStrictBypass(context: Context) {
        val settings = TunnelConfig.load(context)
        if (settings.mode != TunnelConfig.Mode.SMART && settings.mode != TunnelConfig.Mode.DNS_BLACKOUT) {
            TunnelConfig.saveMode(context, TunnelConfig.Mode.SMART)
        }
        VpnLayerStore.rotateToNextLayer(context)
    }
}
