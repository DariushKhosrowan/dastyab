package com.dastyab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * VpnLayerStore — manages multiple customizable VPN Layers / Profiles so users can
 * add, edit, reorder, benchmark, rotate, and 1-tap switch between layers
 * (Smart Tunnel, Global VPN, Split Apps, VLESS Edge, DNS Blackout DoH, Custom Proxy).
 */
object VpnLayerStore {

    private const val PREFS_NAME = "dastyab_vpn_layers"
    private const val KEY_LAYERS_JSON = "layers_json"
    private const val KEY_ACTIVE_LAYER_ID = "active_layer_id"

    data class VpnLayer(
        val id: String = UUID.randomUUID().toString().take(8),
        val name: String,
        val mode: TunnelConfig.Mode,
        val configUri: String = "",
        val dnsPrimary: String = TunnelConfig.DEFAULT_DNS_PRIMARY,
        val dnsSecondary: String = TunnelConfig.DEFAULT_DNS_SECONDARY,
        val dohUrl: String = TunnelConfig.DEFAULT_DOH_URL,
        val mtu: Int = TunnelConfig.DEFAULT_MTU,
        val bypassLan: Boolean = true,
        val autoVpnForGoogle: Boolean = true,
        val notes: String = ""
    ) {
        fun subtitle(): String {
            val ep = TunnelConfig.parseVlessUri(configUri)?.summary()
                ?: if (mode == TunnelConfig.Mode.DNS_BLACKOUT) dohUrl.removePrefix("https://")
                else if (configUri.isNotBlank()) "${ConfigHarvester.protocolOf(configUri).uppercase()} endpoint"
                else "Cloudflare Edge Smart DNS"
            return "${mode.label} · $ep · DNS $dnsPrimary"
        }

        fun probeTarget(): Pair<String, Int> {
            val vless = TunnelConfig.parseVlessUri(configUri)
            if (vless != null) return vless.host to vless.port
            if (mode == TunnelConfig.Mode.DNS_BLACKOUT) {
                val host = dohUrl.removePrefix("https://").substringBefore('/').substringBefore(':')
                return (host.ifBlank { dnsPrimary }) to 443
            }
            return dnsPrimary to 443
        }
    }

    data class LayerBenchmark(
        val layer: VpnLayer,
        val latencyMs: Long,
        val reachable: Boolean
    )

    fun defaultLayers(): List<VpnLayer> = listOf(
        VpnLayer(
            id = "layer-smart",
            name = "Smart Tunnel (AI Studio & Flow)",
            mode = TunnelConfig.Mode.SMART,
            dnsPrimary = "1.1.1.1",
            dnsSecondary = "8.8.8.8",
            notes = "Default smart routing with RFC1918 LAN bypass"
        ),
        VpnLayer(
            id = "layer-global",
            name = "Global Full-Device VPN",
            mode = TunnelConfig.Mode.GLOBAL,
            dnsPrimary = "1.1.1.1",
            dnsSecondary = "1.0.0.1",
            bypassLan = false,
            notes = "Routes all device traffic through the tunnel"
        ),
        VpnLayer(
            id = "layer-split",
            name = "Split Tunnel (Browser / Selected Apps)",
            mode = TunnelConfig.Mode.SPLIT_INCLUDE,
            dnsPrimary = "1.1.1.1",
            dnsSecondary = "8.8.8.8",
            notes = "Only selected apps use the VPN tunnel"
        ),
        VpnLayer(
            id = "layer-dns-blackout",
            name = "Emergency DNS Blackout (WhiteDNS / DoH)",
            mode = TunnelConfig.Mode.DNS_BLACKOUT,
            dnsPrimary = "1.1.1.1",
            dnsSecondary = "9.9.9.9",
            dohUrl = TunnelConfig.DEFAULT_DOH_URL,
            notes = "Last-resort DoH + clean UDP DNS tunnel for severe blackouts"
        )
    )

    fun loadAll(context: Context): List<VpnLayer> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LAYERS_JSON, null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultLayers()
            saveAll(context, defaults)
            return defaults
        }
        val parsed = deserializeLayers(raw)
        return if (parsed.isEmpty()) defaultLayers() else parsed
    }

    fun saveAll(context: Context, layers: List<VpnLayer>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAYERS_JSON, serializeLayers(layers)).apply()
    }

    fun getActiveLayerId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_LAYER_ID, "layer-smart") ?: "layer-smart"
    }

    fun getActiveLayer(context: Context): VpnLayer {
        val layers = loadAll(context)
        val id = getActiveLayerId(context)
        return layers.firstOrNull { it.id == id } ?: layers.first()
    }

    fun addOrUpdate(context: Context, layer: VpnLayer): List<VpnLayer> {
        val current = loadAll(context).toMutableList()
        val idx = current.indexOfFirst { it.id == layer.id }
        if (idx >= 0) {
            current[idx] = layer
        } else {
            current.add(0, layer)
        }
        saveAll(context, current)
        return current
    }

    fun delete(context: Context, layerId: String): List<VpnLayer> {
        val current = loadAll(context).filterNot { it.id == layerId }
        val finalList = if (current.isEmpty()) defaultLayers() else current
        saveAll(context, finalList)
        if (getActiveLayerId(context) == layerId) {
            activateLayer(context, finalList.first(), restartIfConnected = false)
        }
        return finalList
    }

    /** Applies [layer] into [TunnelConfig] and optionally restarts [DastyabVpnService] if connected. */
    fun activateLayer(context: Context, layer: VpnLayer, restartIfConnected: Boolean = true) {
        val existing = TunnelConfig.load(context)
        val updated = existing.copy(
            mode = layer.mode,
            vlessUri = if (layer.configUri.startsWith("vless://", ignoreCase = true)) layer.configUri else existing.vlessUri,
            dnsPrimary = TunnelConfig.sanitizeDns(layer.dnsPrimary, TunnelConfig.DEFAULT_DNS_PRIMARY),
            dnsSecondary = TunnelConfig.sanitizeDns(layer.dnsSecondary, TunnelConfig.DEFAULT_DNS_SECONDARY),
            dohUrl = TunnelConfig.sanitizeDohUrl(layer.dohUrl),
            mtu = TunnelConfig.clampMtu(layer.mtu),
            bypassLan = layer.bypassLan,
            autoVpnForGoogle = layer.autoVpnForGoogle
        )
        TunnelConfig.save(context, updated)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_LAYER_ID, layer.id)
            .apply()
        if (restartIfConnected && DastyabVpnService.isConnected()) {
            DastyabVpnService.start(context)
        }
    }

    /** Rotates to the next VPN layer in the list (used by Notification & Quick Settings accessibility). */
    fun rotateToNextLayer(context: Context): VpnLayer {
        val layers = loadAll(context)
        val activeId = getActiveLayerId(context)
        val idx = layers.indexOfFirst { it.id == activeId }
        val next = layers[(idx + 1).mod(layers.size)]
        activateLayer(context, next, restartIfConnected = true)
        return next
    }

    /** Benchmarks all saved layers in parallel and returns them sorted fastest-first. */
    fun benchmarkLayers(layers: List<VpnLayer>, timeoutMs: Int = 2000): List<LayerBenchmark> {
        if (layers.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(layers.size.coerceAtMost(6))
        return try {
            val tasks = layers.map { layer ->
                Callable {
                    val (host, port) = layer.probeTarget()
                    val ms = if (layer.mode == TunnelConfig.Mode.DNS_BLACKOUT) {
                        CleanEndpointScout.probeDohLatency(layer.dohUrl, timeoutMs)
                    } else {
                        CleanEndpointScout.probeTcpLatency(host, port, timeoutMs)
                    }
                    LayerBenchmark(layer, ms, ms >= 0)
                }
            }
            pool.invokeAll(tasks, 10, TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .sortedWith(compareBy<LayerBenchmark> { !it.reachable }.thenBy { if (it.latencyMs < 0) Long.MAX_VALUE else it.latencyMs })
        } finally {
            pool.shutdownNow()
        }
    }

    /** Pure-Kotlin JSON serializer (unit-testable on the JVM). */
    fun serializeLayers(layers: List<VpnLayer>): String {
        val arr = JSONArray()
        for (l in layers) {
            arr.put(JSONObject().apply {
                put("id", l.id)
                put("name", l.name)
                put("mode", l.mode.name)
                put("configUri", l.configUri)
                put("dnsPrimary", l.dnsPrimary)
                put("dnsSecondary", l.dnsSecondary)
                put("dohUrl", l.dohUrl)
                put("mtu", l.mtu)
                put("bypassLan", l.bypassLan)
                put("autoVpnForGoogle", l.autoVpnForGoogle)
                put("notes", l.notes)
            })
        }
        return arr.toString()
    }

    /** Pure-Kotlin JSON deserializer (unit-testable on the JVM). */
    fun deserializeLayers(json: String): List<VpnLayer> {
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<VpnLayer>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                if (name.isEmpty()) continue
                list.add(
                    VpnLayer(
                        id = o.optString("id", UUID.randomUUID().toString().take(8)),
                        name = name,
                        mode = TunnelConfig.Mode.fromName(o.optString("mode", TunnelConfig.Mode.SMART.name)),
                        configUri = o.optString("configUri", ""),
                        dnsPrimary = TunnelConfig.sanitizeDns(o.optString("dnsPrimary", ""), TunnelConfig.DEFAULT_DNS_PRIMARY),
                        dnsSecondary = TunnelConfig.sanitizeDns(o.optString("dnsSecondary", ""), TunnelConfig.DEFAULT_DNS_SECONDARY),
                        dohUrl = TunnelConfig.sanitizeDohUrl(o.optString("dohUrl", "")),
                        mtu = TunnelConfig.clampMtu(o.optInt("mtu", TunnelConfig.DEFAULT_MTU)),
                        bypassLan = o.optBoolean("bypassLan", true),
                        autoVpnForGoogle = o.optBoolean("autoVpnForGoogle", true),
                        notes = o.optString("notes", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
