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

    const val DEFAULT_DNS_PRIMARY = "1.1.1.1"
    const val DEFAULT_DNS_SECONDARY = "8.8.8.8"
    const val DEFAULT_MTU = 1500
    const val MIN_MTU = 1280
    const val MAX_MTU = 1500

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
        );

        companion object {
            fun fromName(name: String?): Mode {
                if (name.isNullOrBlank()) return SMART
                return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SMART
            }
        }
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
        val splitPackages: Set<String> = emptySet()
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
            splitPackages = normalizePackages(rawPkgs)
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
            .apply()
    }

    fun saveMode(context: Context, mode: Mode) {
        val current = load(context)
        save(context, current.copy(mode = mode))
    }

    fun saveVlessUri(context: Context, uri: String) {
        val current = load(context)
        save(context, current.copy(vlessUri = uri.trim()))
    }

    fun clampMtu(mtu: Int): Int = mtu.coerceIn(MIN_MTU, MAX_MTU)

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
