package com.dastyab.app

import android.content.Context
import android.content.SharedPreferences
import java.net.URI
import java.net.URLDecoder

/**
 * TunnelConfig — persistent VPN, Smart Tunnel, and Split Tunnel configuration.
 *
 * Pure Kotlin model + VLESS URI parser so every parsing/validation rule is
 * unit-testable on the plain JVM without Android runtime dependencies.
 */
object TunnelConfig {

    const val PREFS_NAME = "dastyab_tunnel"

    private const val KEY_MODE = "tunnel_mode"
    private const val KEY_VLESS_URI = "vless_uri"
    private const val KEY_DNS_PRIMARY = "dns_primary"
    private const val KEY_DNS_SECONDARY = "dns_secondary"
    private const val KEY_MTU = "mtu"
    private const val KEY_BYPASS_LAN = "bypass_lan"
    private const val KEY_AUTO_VPN_GOOGLE = "auto_vpn_google"
    private const val KEY_SPLIT_PACKAGES = "split_packages"
    private const val KEY_DOH_URL = "doh_url"
    private const val KEY_DNS_TUNNEL_DOMAIN = "dns_tunnel_domain"
    private const val KEY_BOOTSTRAP_CONFIG = "bootstrap_config"
    private const val KEY_HARVESTED_CONFIGS = "harvested_configs"
    private const val KEY_AUTO_CONNECT_BOOT = "auto_connect_on_boot"
    private const val KEY_WATCHDOG_ENABLED = "watchdog_enabled"
    private const val KEY_GOOGLE_ACCESS_MODE = "google_access_mode"
    private const val KEY_UPSTREAM_PROXY_SCHEME = "upstream_proxy_scheme"
    private const val KEY_UPSTREAM_PROXY_HOST = "upstream_proxy_host"
    private const val KEY_UPSTREAM_PROXY_PORT = "upstream_proxy_port"
    private const val KEY_LOCAL_BRIDGE_PORT = "local_bridge_port"
    private const val KEY_AUTO_START_LOCAL_BRIDGE = "auto_start_local_bridge"

    const val DEFAULT_DNS_PRIMARY = "1.1.1.1"
    const val DEFAULT_DNS_SECONDARY = "8.8.8.8"
    const val DEFAULT_DOH_URL = "https://1.1.1.1/dns-query"
    const val DEFAULT_MTU = 1500
    const val MIN_MTU = 1280
    const val MAX_MTU = 1500
    const val DEFAULT_PROXY_HOST = "127.0.0.1"
    const val DEFAULT_UPSTREAM_PROXY_PORT = 10809
    const val DEFAULT_LOCAL_BRIDGE_PORT = 10850
    const val MIN_LOCAL_PORT = 1024
    const val MAX_PORT = 65535

    enum class Mode(val label: String, val subtitle: String) {
        SMART(
            "Smart Tunnel",
            "Routes international, AI Studio & Google Flow traffic while bypassing LAN"
        ),
        GLOBAL(
            "Global VPN",
            "Routes all device traffic through the DastYab tunnel"
        ),
        SPLIT_INCLUDE(
            "Split Tunnel (Selected Apps)",
            "Only selected apps (e.g. Chrome for AI Studio / Flow) use the tunnel"
        ),
        SPLIT_EXCLUDE(
            "Split Tunnel (Exclude Apps)",
            "All apps use the tunnel except the excluded local/banking apps"
        ),
        DNS_BLACKOUT(
            "DNS Blackout VPN (DoH / WhiteDNS)",
            "Emergency DNS-over-HTTPS & clean DNS tunnel for severe blackouts and DNS poisoning"
        );

        companion object {
            fun fromName(name: String?): Mode {
                if (name.isNullOrBlank()) return SMART
                return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SMART
            }
        }

        fun localizedLabel(context: Context): String = context.getString(
            when (this) {
                SMART -> R.string.tunnel_mode_smart_label
                GLOBAL -> R.string.tunnel_mode_global_label
                SPLIT_INCLUDE -> R.string.tunnel_mode_split_include_label
                SPLIT_EXCLUDE -> R.string.tunnel_mode_split_exclude_label
                DNS_BLACKOUT -> R.string.tunnel_mode_dns_blackout_label
            }
        )

        fun localizedSubtitle(context: Context): String = context.getString(
            when (this) {
                SMART -> R.string.tunnel_mode_smart_sub
                GLOBAL -> R.string.tunnel_mode_global_sub
                SPLIT_INCLUDE -> R.string.tunnel_mode_split_include_sub
                SPLIT_EXCLUDE -> R.string.tunnel_mode_split_exclude_sub
                DNS_BLACKOUT -> R.string.tunnel_mode_dns_blackout_sub
            }
        )
    }

    enum class GoogleAccessMode(val label: String, val subtitle: String) {
        AUTO_SMART(
            "Auto Smart (Lantern / Proxy / VPN)",
            "Never steals an active external VPN like Lantern; auto-uses listening local proxies or DastYab VPN"
        ),
        EXTERNAL_VPN(
            "External VPN (Lantern / System)",
            "Keeps Android VPN slot free so Lantern or another VPN app runs on top cleanly"
        ),
        IN_APP_PROXY(
            "Internal App Proxy (Multi-Layer)",
            "Routes AI Studio & Flow inside DastYab Browser via local proxy while another VPN runs on top"
        ),
        VPN_CHAIN_PROXY(
            "DastYab VPN + Local Proxy Chain",
            "Runs DastYab VpnService chaining HTTP/HTTPS traffic through a local proxy port (API 29+)"
        ),
        LOCAL_BRIDGE(
            "DastYab Local Proxy Port (For v2rayNG)",
            "Exposes DastYab local HTTP + SOCKS5 port (127.0.0.1:<port>) for v2rayNG, Chrome & browser"
        );

        companion object {
            fun fromName(name: String?): GoogleAccessMode {
                if (name.isNullOrBlank()) return AUTO_SMART
                return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO_SMART
            }
        }

        fun localizedLabel(context: Context): String = context.getString(
            when (this) {
                AUTO_SMART -> R.string.google_access_auto_label
                EXTERNAL_VPN -> R.string.google_access_external_label
                IN_APP_PROXY -> R.string.google_access_inapp_label
                VPN_CHAIN_PROXY -> R.string.google_access_chain_label
                LOCAL_BRIDGE -> R.string.google_access_bridge_label
            }
        )

        fun localizedSubtitle(context: Context): String = context.getString(
            when (this) {
                AUTO_SMART -> R.string.google_access_auto_sub
                EXTERNAL_VPN -> R.string.google_access_external_sub
                IN_APP_PROXY -> R.string.google_access_inapp_sub
                VPN_CHAIN_PROXY -> R.string.google_access_chain_sub
                LOCAL_BRIDGE -> R.string.google_access_bridge_sub
            }
        )
    }

    enum class ProxyScheme(val label: String) {
        DIRECT("Direct / Active System VPN"),
        HTTP("HTTP Proxy"),
        SOCKS5("SOCKS5 Proxy");

        companion object {
            fun fromName(name: String?): ProxyScheme {
                if (name.isNullOrBlank()) return DIRECT
                return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DIRECT
            }
        }

        fun localizedLabel(context: Context): String = context.getString(
            when (this) {
                DIRECT -> R.string.proxy_scheme_direct
                HTTP -> R.string.proxy_scheme_http
                SOCKS5 -> R.string.proxy_scheme_socks5
            }
        )
    }

    data class VlessEndpoint(
        val uuid: String,
        val host: String,
        val port: Int,
        val sni: String,
        val path: String,
        val security: String,
        val transport: String,
        val label: String
    ) {
        fun summary(): String {
            val tag = if (label.isNotBlank()) "$label · " else ""
            return "$tag$host:$port ($transport/$security)"
        }
    }

    data class Settings(
        val mode: Mode = Mode.SMART,
        val vlessUri: String = "",
        val dnsPrimary: String = DEFAULT_DNS_PRIMARY,
        val dnsSecondary: String = DEFAULT_DNS_SECONDARY,
        val mtu: Int = DEFAULT_MTU,
        val bypassLan: Boolean = true,
        val autoVpnForGoogle: Boolean = true,
        val splitPackages: Set<String> = emptySet(),
        val dohUrl: String = DEFAULT_DOH_URL,
        val dnsTunnelDomain: String = "",
        val bootstrapConfig: String = "",
        val autoConnectOnBoot: Boolean = false,
        val watchdogEnabled: Boolean = true,
        val googleAccessMode: GoogleAccessMode = GoogleAccessMode.AUTO_SMART,
        val upstreamProxyScheme: ProxyScheme = ProxyScheme.DIRECT,
        val upstreamProxyHost: String = DEFAULT_PROXY_HOST,
        val upstreamProxyPort: Int = DEFAULT_UPSTREAM_PROXY_PORT,
        val localBridgePort: Int = DEFAULT_LOCAL_BRIDGE_PORT,
        val autoStartLocalBridge: Boolean = false
    ) {
        fun parsedEndpoint(): VlessEndpoint? = parseVlessUri(vlessUri)
    }

    fun load(context: Context): Settings {
        val prefs = prefs(context)
        val rawPkgs = prefs.getStringSet(KEY_SPLIT_PACKAGES, emptySet()) ?: emptySet()
        return Settings(
            mode = Mode.fromName(prefs.getString(KEY_MODE, Mode.SMART.name)),
            vlessUri = prefs.getString(KEY_VLESS_URI, "") ?: "",
            dnsPrimary = sanitizeDns(prefs.getString(KEY_DNS_PRIMARY, DEFAULT_DNS_PRIMARY), DEFAULT_DNS_PRIMARY),
            dnsSecondary = sanitizeDns(prefs.getString(KEY_DNS_SECONDARY, DEFAULT_DNS_SECONDARY), DEFAULT_DNS_SECONDARY),
            mtu = clampMtu(prefs.getInt(KEY_MTU, DEFAULT_MTU)),
            bypassLan = prefs.getBoolean(KEY_BYPASS_LAN, true),
            autoVpnForGoogle = prefs.getBoolean(KEY_AUTO_VPN_GOOGLE, true),
            splitPackages = normalizePackages(rawPkgs),
            dohUrl = sanitizeDohUrl(prefs.getString(KEY_DOH_URL, DEFAULT_DOH_URL)),
            dnsTunnelDomain = (prefs.getString(KEY_DNS_TUNNEL_DOMAIN, "") ?: "").trim(),
            bootstrapConfig = (prefs.getString(KEY_BOOTSTRAP_CONFIG, "") ?: "").trim(),
            autoConnectOnBoot = prefs.getBoolean(KEY_AUTO_CONNECT_BOOT, false),
            watchdogEnabled = prefs.getBoolean(KEY_WATCHDOG_ENABLED, true),
            googleAccessMode = GoogleAccessMode.fromName(prefs.getString(KEY_GOOGLE_ACCESS_MODE, GoogleAccessMode.AUTO_SMART.name)),
            upstreamProxyScheme = ProxyScheme.fromName(prefs.getString(KEY_UPSTREAM_PROXY_SCHEME, ProxyScheme.DIRECT.name)),
            upstreamProxyHost = sanitizeProxyHost(prefs.getString(KEY_UPSTREAM_PROXY_HOST, DEFAULT_PROXY_HOST)),
            upstreamProxyPort = clampPort(prefs.getInt(KEY_UPSTREAM_PROXY_PORT, DEFAULT_UPSTREAM_PROXY_PORT), DEFAULT_UPSTREAM_PROXY_PORT, min = 1),
            localBridgePort = clampPort(prefs.getInt(KEY_LOCAL_BRIDGE_PORT, DEFAULT_LOCAL_BRIDGE_PORT), DEFAULT_LOCAL_BRIDGE_PORT, min = MIN_LOCAL_PORT),
            autoStartLocalBridge = prefs.getBoolean(KEY_AUTO_START_LOCAL_BRIDGE, false)
        )
    }

    fun save(context: Context, settings: Settings) {
        prefs(context).edit()
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_VLESS_URI, settings.vlessUri.trim())
            .putString(KEY_DNS_PRIMARY, sanitizeDns(settings.dnsPrimary, DEFAULT_DNS_PRIMARY))
            .putString(KEY_DNS_SECONDARY, sanitizeDns(settings.dnsSecondary, DEFAULT_DNS_SECONDARY))
            .putInt(KEY_MTU, clampMtu(settings.mtu))
            .putBoolean(KEY_BYPASS_LAN, settings.bypassLan)
            .putBoolean(KEY_AUTO_VPN_GOOGLE, settings.autoVpnForGoogle)
            .putStringSet(KEY_SPLIT_PACKAGES, normalizePackages(settings.splitPackages))
            .putString(KEY_DOH_URL, sanitizeDohUrl(settings.dohUrl))
            .putString(KEY_DNS_TUNNEL_DOMAIN, settings.dnsTunnelDomain.trim())
            .putString(KEY_BOOTSTRAP_CONFIG, settings.bootstrapConfig.trim())
            .putBoolean(KEY_AUTO_CONNECT_BOOT, settings.autoConnectOnBoot)
            .putBoolean(KEY_WATCHDOG_ENABLED, settings.watchdogEnabled)
            .putString(KEY_GOOGLE_ACCESS_MODE, settings.googleAccessMode.name)
            .putString(KEY_UPSTREAM_PROXY_SCHEME, settings.upstreamProxyScheme.name)
            .putString(KEY_UPSTREAM_PROXY_HOST, sanitizeProxyHost(settings.upstreamProxyHost))
            .putInt(KEY_UPSTREAM_PROXY_PORT, clampPort(settings.upstreamProxyPort, DEFAULT_UPSTREAM_PROXY_PORT, min = 1))
            .putInt(KEY_LOCAL_BRIDGE_PORT, clampPort(settings.localBridgePort, DEFAULT_LOCAL_BRIDGE_PORT, min = MIN_LOCAL_PORT))
            .putBoolean(KEY_AUTO_START_LOCAL_BRIDGE, settings.autoStartLocalBridge)
            .apply()
    }

    fun saveGoogleProxyConfig(
        context: Context,
        accessMode: GoogleAccessMode,
        scheme: ProxyScheme,
        host: String,
        port: Int,
        localBridgePort: Int = load(context).localBridgePort,
        autoStartBridge: Boolean = load(context).autoStartLocalBridge
    ) {
        val current = load(context)
        save(
            context,
            current.copy(
                googleAccessMode = accessMode,
                upstreamProxyScheme = scheme,
                upstreamProxyHost = sanitizeProxyHost(host),
                upstreamProxyPort = clampPort(port, DEFAULT_UPSTREAM_PROXY_PORT, min = 1),
                localBridgePort = clampPort(localBridgePort, DEFAULT_LOCAL_BRIDGE_PORT, min = MIN_LOCAL_PORT),
                autoStartLocalBridge = autoStartBridge
            )
        )
    }

    fun saveMode(context: Context, mode: Mode) {
        val current = load(context)
        save(context, current.copy(mode = mode))
    }

    fun saveVlessUri(context: Context, uri: String) {
        val current = load(context)
        save(context, current.copy(vlessUri = uri.trim()))
    }

    fun saveBootstrapConfig(context: Context, config: String) {
        val current = load(context)
        val trimmed = config.trim()
        val updatedVless = if (trimmed.startsWith("vless://", ignoreCase = true)) trimmed else current.vlessUri
        save(context, current.copy(bootstrapConfig = trimmed, vlessUri = updatedVless))
    }

    fun saveDohAndDns(context: Context, dohUrl: String?, primaryDns: String?, dnsTunnelDomain: String? = null) {
        val current = load(context)
        save(
            context,
            current.copy(
                dohUrl = sanitizeDohUrl(dohUrl ?: current.dohUrl),
                dnsPrimary = sanitizeDns(primaryDns ?: current.dnsPrimary, current.dnsPrimary),
                dnsTunnelDomain = dnsTunnelDomain?.trim() ?: current.dnsTunnelDomain
            )
        )
    }

    fun saveHarvestedConfigs(context: Context, configs: List<String>) {
        val cleaned = configs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(300)
        prefs(context).edit()
            .putString(KEY_HARVESTED_CONFIGS, cleaned.joinToString("\n"))
            .apply()
    }

    fun loadHarvestedConfigs(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_HARVESTED_CONFIGS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    fun sanitizeDohUrl(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        return if (trimmed.startsWith("https://", ignoreCase = true) && trimmed.length > 10) {
            trimmed
        } else {
            DEFAULT_DOH_URL
        }
    }

    fun clampMtu(mtu: Int): Int = mtu.coerceIn(MIN_MTU, MAX_MTU)

    fun clampPort(port: Int, fallback: Int = DEFAULT_UPSTREAM_PROXY_PORT, min: Int = 1): Int {
        return if (port in min..MAX_PORT) port else fallback
    }

    fun sanitizeProxyHost(input: String?): String {
        val candidate = input?.trim().orEmpty()
        if (candidate.isEmpty() || candidate.any { it.isWhitespace() || it == '/' || it == '@' || it == ':' }) {
            return DEFAULT_PROXY_HOST
        }
        return candidate
    }

    fun isValidIpv4(ip: String): Boolean {
        val trimmed = ip.trim()
        val parts = trimmed.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } &&
                (part.toIntOrNull()?.let { it in 0..255 } ?: false)
        }
    }

    fun sanitizeDns(input: String?, fallback: String): String {
        val candidate = input?.trim().orEmpty()
        return if (isValidIpv4(candidate)) candidate else fallback
    }

    fun normalizePackages(packages: Collection<String>): Set<String> {
        return packages
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "com.dastyab.app" }
            .toSortedSet()
    }

    /**
     * Parse a portable `vless://uuid@host:port?security=tls&type=ws&sni=...&path=/#label`
     * share link into a structured [VlessEndpoint]. Returns null if invalid.
     */
    fun parseVlessUri(raw: String?): VlessEndpoint? {
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) return null
        return try {
            val uri = URI(trimmed)
            val uuid = uri.userInfo?.trim().orEmpty()
            val host = uri.host?.trim().orEmpty()
            if (uuid.isEmpty() || host.isEmpty()) return null
            val port = if (uri.port in 1..65535) uri.port else 443

            val params = mutableMapOf<String, String>()
            uri.rawQuery?.split('&')?.forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = pair.substring(0, idx).lowercase()
                    val value = decodePercent(pair.substring(idx + 1))
                    params[key] = value
                }
            }

            val sni = params["sni"]?.takeIf { it.isNotBlank() }
                ?: params["host"]?.takeIf { it.isNotBlank() }
                ?: host
            val path = params["path"]?.takeIf { it.isNotBlank() } ?: "/"
            val security = params["security"]?.takeIf { it.isNotBlank() } ?: "tls"
            val transport = params["type"]?.takeIf { it.isNotBlank() } ?: "ws"
            val label = uri.rawFragment?.let { decodePercent(it) }?.trim().orEmpty()

            VlessEndpoint(
                uuid = uuid,
                host = host,
                port = port,
                sni = sni,
                path = path,
                security = security,
                transport = transport,
                label = label
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePercent(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
