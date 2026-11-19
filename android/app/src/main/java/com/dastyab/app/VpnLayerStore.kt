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
        val notes: String = "",
        val upstreamProxyScheme: TunnelConfig.ProxyScheme = TunnelConfig.ProxyScheme.DIRECT,
        val upstreamProxyHost: String = TunnelConfig.DEFAULT_PROXY_HOST,
        val upstreamProxyPort: Int = TunnelConfig.DEFAULT_UPSTREAM_PROXY_PORT,
        val roleLabel: String = ""
    ) {
        fun subtitle(): String {
            val ep = TunnelConfig.parseVlessUri(configUri)?.summary()
                ?: UniversalConfig.parse(configUri)?.summary()
                ?: if (mode == TunnelConfig.Mode.DNS_BLACKOUT) dohUrl.removePrefix("https://")
                else if (configUri.isNotBlank()) "${UniversalConfig.protocolOf(configUri).uppercase()} endpoint"
                else "Cloudflare Edge Smart DNS"
            val chainTag = if (upstreamProxyScheme != TunnelConfig.ProxyScheme.DIRECT) {
                " · Chain ${upstreamProxyScheme.name} $upstreamProxyHost:$upstreamProxyPort"
            } else ""
            return "${mode.label} · $ep · DNS $dnsPrimary$chainTag"
        }

        fun probeTarget(): Pair<String, Int> {
            val parsed = UniversalConfig.parse(configUri)
            if (parsed != null) return parsed.host to parsed.port
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
        val isVless = layer.configUri.startsWith("vless://", ignoreCase = true)
        val chainActive = layer.upstreamProxyScheme != TunnelConfig.ProxyScheme.DIRECT
        val updated = existing.copy(
            mode = layer.mode,
            vlessUri = if (isVless) layer.configUri else existing.vlessUri,
            bootstrapConfig = if (!isVless && layer.configUri.isNotBlank()) layer.configUri else existing.bootstrapConfig,
            dnsPrimary = TunnelConfig.sanitizeDns(layer.dnsPrimary, TunnelConfig.DEFAULT_DNS_PRIMARY),
            dnsSecondary = TunnelConfig.sanitizeDns(layer.dnsSecondary, TunnelConfig.DEFAULT_DNS_SECONDARY),
            dohUrl = TunnelConfig.sanitizeDohUrl(layer.dohUrl),
            mtu = TunnelConfig.clampMtu(layer.mtu),
            bypassLan = layer.bypassLan,
            autoVpnForGoogle = layer.autoVpnForGoogle,
            upstreamProxyScheme = layer.upstreamProxyScheme,
            upstreamProxyHost = TunnelConfig.sanitizeProxyHost(layer.upstreamProxyHost),
            upstreamProxyPort = TunnelConfig.clampPort(layer.upstreamProxyPort, TunnelConfig.DEFAULT_UPSTREAM_PROXY_PORT, min = 1),
            googleAccessMode = if (chainActive) TunnelConfig.GoogleAccessMode.VPN_CHAIN_PROXY else existing.googleAccessMode
        )
        TunnelConfig.save(context, updated)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_LAYER_ID, layer.id)
            .apply()
        if (chainActive && updated.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5) {
            LocalProxyService.startSafely(context)
        }
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

    /**
     * Scans harvested multi-protocol endpoints + open local proxy ports and assembles a hardened
     * 5+ layer bypass stack combining:
     *  - Layer 1: Fastest Edge VLESS/Trojan/Hy2 Smart Tunnel
     *  - Layer 2: WireGuard / WARP & OpenVPN protocol-diversity gateway layers (when present)
     *  - Layer 3: Local Proxy Cascade Bridge (wrapping v2rayNG / NekoBox / Orbot / Clash inside DoH VPN)
     *  - Layer 4: Alt-DoH Global Tunnel (9.9.9.9 / dns.google)
     *  - Layer 5: Emergency WhiteDNS Blackout Shield
     */
    fun buildHardenedResilienceStack(context: Context): List<VpnLayer> {
        val pool = TunnelConfig.loadHarvestedConfigs(context)
        val existing = loadAll(context)
        val built = mutableListOf<VpnLayer>()

        fun pickByProtocols(vararg protos: String): String? {
            val targetSet = protos.map { it.lowercase() }.toSet()
            return pool.firstOrNull { UniversalConfig.protocolOf(it).lowercase() in targetSet }
        }

        pickByProtocols("vless")?.let { uri ->
            val host = UniversalConfig.extractHostAndPort(uri)?.first ?: "Edge"
            built.add(
                VpnLayer(
                    id = "shield-vless",
                    name = "Layer 1 · VLESS Edge ($host)",
                    mode = TunnelConfig.Mode.SMART,
                    configUri = uri,
                    dnsPrimary = "1.1.1.1",
                    dnsSecondary = "1.0.0.1",
                    dohUrl = "https://1.1.1.1/dns-query",
                    roleLabel = "EDGE_VLESS"
                )
            )
        }
        pickByProtocols("hy2", "hysteria2", "hysteria", "tuic")?.let { uri ->
            val parsed = UniversalConfig.parse(uri)
            built.add(
                VpnLayer(
                    id = "shield-quic",
                    name = "Layer 2 · ${parsed?.protocol?.uppercase() ?: "QUIC"} (${parsed?.host ?: "Fast"})",
                    mode = TunnelConfig.Mode.SMART,
                    configUri = uri,
                    dnsPrimary = "1.1.1.1",
                    dnsSecondary = "8.8.8.8",
                    dohUrl = "https://dns.google/dns-query",
                    roleLabel = "QUIC_STEALTH"
                )
            )
        }
        pickByProtocols("wireguard", "wg", "warp", "amneziawg")?.let { uri ->
            val host = UniversalConfig.extractHostAndPort(uri)?.first ?: "WG"
            built.add(
                VpnLayer(
                    id = "shield-wireguard",
                    name = "Layer · WireGuard/WARP ($host)",
                    mode = TunnelConfig.Mode.SMART,
                    configUri = uri,
                    dnsPrimary = "1.1.1.1",
                    dnsSecondary = "9.9.9.9",
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                    mtu = 1280,
                    roleLabel = "WIREGUARD"
                )
            )
        }
        pickByProtocols("openvpn", "ovpn")?.let { uri ->
            val host = UniversalConfig.extractHostAndPort(uri)?.first ?: "OVPN"
            built.add(
                VpnLayer(
                    id = "shield-openvpn",
                    name = "Layer · OpenVPN ($host)",
                    mode = TunnelConfig.Mode.GLOBAL,
                    configUri = uri,
                    dnsPrimary = "9.9.9.9",
                    dnsSecondary = "8.8.8.8",
                    dohUrl = "https://9.9.9.9/dns-query",
                    roleLabel = "OPENVPN"
                )
            )
        }
        pickByProtocols("npv4", "npvs", "npv", "npvt", "napsternetv", "trojan", "vmess", "ss")?.let { uri ->
            val parsed = UniversalConfig.parse(uri)
            built.add(
                VpnLayer(
                    id = "shield-altproto",
                    name = "Layer · ${parsed?.protocol?.uppercase() ?: "Alt"} (${parsed?.host ?: "Relay"})",
                    mode = TunnelConfig.Mode.SMART,
                    configUri = uri,
                    dnsPrimary = "8.8.8.8",
                    dnsSecondary = "1.1.1.1",
                    dohUrl = "https://dns.google/dns-query",
                    roleLabel = "ALT_PROTO"
                )
            )
        }

        // Auto-detect active local proxy ports (HTTP / SOCKS5) to build a true double-hop cascade layer
        val detectedLocal = detectListeningLocalProxy()
        if (detectedLocal != null) {
            val (scheme, port, label) = detectedLocal
            built.add(
                VpnLayer(
                    id = "shield-chain-$port",
                    name = "Layer · Double-Hop Chain ($label :$port)",
                    mode = TunnelConfig.Mode.SMART,
                    dnsPrimary = "1.1.1.1",
                    dnsSecondary = "8.8.8.8",
                    dohUrl = "https://1.1.1.1/dns-query",
                    upstreamProxyScheme = scheme,
                    upstreamProxyHost = "127.0.0.1",
                    upstreamProxyPort = port,
                    roleLabel = "CHAIN_BRIDGE",
                    notes = "Routes device DNS over DoH + HTTP/SOCKS5 traffic through local $label ($port)"
                )
            )
        }

        // Ensure core resilient defaults remain in stack
        val builtIds = built.map { it.id }.toSet()
        for (def in defaultLayers()) {
            if (def.id !in builtIds) built.add(def)
        }
        for (custom in existing) {
            if (custom.id !in built.map { it.id }) built.add(custom)
        }

        val finalStack = built.distinctBy { it.id }.take(14)
        saveAll(context, finalStack)
        return finalStack
    }

    /** Finds fastest reachable backup layer excluding [excludeLayerId] for automatic watchdog failover. */
    fun findFastestBackupLayer(context: Context, excludeLayerId: String, timeoutMs: Int = 1800): LayerBenchmark? {
        val candidates = loadAll(context).filter { it.id != excludeLayerId }
        if (candidates.isEmpty()) return null
        return benchmarkLayers(candidates, timeoutMs).firstOrNull { it.reachable }
    }

    private fun detectListeningLocalProxy(): Triple<TunnelConfig.ProxyScheme, Int, String>? {
        val probes = listOf(
            Triple(TunnelConfig.ProxyScheme.HTTP, 10809, "v2rayNG HTTP"),
            Triple(TunnelConfig.ProxyScheme.HTTP, 12334, "Hiddify/NekoBox HTTP"),
            Triple(TunnelConfig.ProxyScheme.HTTP, 2081, "Clash/Proxy HTTP"),
            Triple(TunnelConfig.ProxyScheme.HTTP, 7890, "Clash Mixed"),
            Triple(TunnelConfig.ProxyScheme.HTTP, 8118, "Orbot Polipo"),
            Triple(TunnelConfig.ProxyScheme.SOCKS5, 10808, "v2rayNG SOCKS5"),
            Triple(TunnelConfig.ProxyScheme.SOCKS5, 2080, "NekoBox SOCKS5"),
            Triple(TunnelConfig.ProxyScheme.SOCKS5, 9050, "Tor/Orbot SOCKS5")
        )
        for ((scheme, port, label) in probes) {
            if (CleanEndpointScout.probeTcpLatency("127.0.0.1", port, 120) >= 0) {
                return Triple(scheme, port, label)
            }
        }
        return null
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
                put("upstreamProxyScheme", l.upstreamProxyScheme.name)
                put("upstreamProxyHost", l.upstreamProxyHost)
                put("upstreamProxyPort", l.upstreamProxyPort)
                put("roleLabel", l.roleLabel)
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
                        notes = o.optString("notes", ""),
                        upstreamProxyScheme = TunnelConfig.ProxyScheme.fromName(o.optString("upstreamProxyScheme", TunnelConfig.ProxyScheme.DIRECT.name)),
                        upstreamProxyHost = TunnelConfig.sanitizeProxyHost(o.optString("upstreamProxyHost", TunnelConfig.DEFAULT_PROXY_HOST)),
                        upstreamProxyPort = TunnelConfig.clampPort(o.optInt("upstreamProxyPort", TunnelConfig.DEFAULT_UPSTREAM_PROXY_PORT), TunnelConfig.DEFAULT_UPSTREAM_PROXY_PORT, min = 1),
                        roleLabel = o.optString("roleLabel", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
