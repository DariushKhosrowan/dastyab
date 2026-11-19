package com.dastyab.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * TerminalCommandRouter — pure-Kotlin command normalization, prompt formatting,
 * profile node serialization, and IPC event parsing for Terminal Mode.
 *
 * Intentionally contains zero Android/JNI dependencies so it is 100% testable
 * on the plain JVM (`testDebugUnitTest`).
 */
object TerminalCommandRouter {

    enum class Action {
        EMPTY,
        CLEAR,
        PYTHON_SHELL
    }

    data class QuickPreset(
        val label: String,
        val command: String,
        val autoRun: Boolean = true
    )

    data class HostEvent(
        val kind: String,
        val payloadJson: String
    )

    val PYTHON_FLOW_PRESETS: List<QuickPreset> = listOf(
        QuickPreset("flow best", "dastyab flow best"),
        QuickPreset("flow start", "dastyab flow start"),
        QuickPreset("flow status", "dastyab flow status"),
        QuickPreset("flow verify", "dastyab flow verify"),
        QuickPreset("flow countries", "dastyab flow countries"),
        QuickPreset("aistudio verify", "dastyab aistudio verify"),
        QuickPreset("aistudio start", "dastyab aistudio start"),
        QuickPreset("flow stop", "dastyab flow stop")
    )

    val LINUX_PYTHON_PRESETS: List<QuickPreset> = listOf(
        QuickPreset("python3 --version", "python3 --version"),
        QuickPreset("ssl & arch test", "python3 -c \"import sys, ssl, platform; print(sys.version); print(ssl.OPENSSL_VERSION, platform.machine())\""),
        QuickPreset("ls ~/.dastyab", "ls -la ~/.dastyab/profiles/default"),
        QuickPreset("cat nodes.json", "head -n 25 ~/.dastyab/profiles/default/nodes.json"),
        QuickPreset("env", "env"),
        QuickPreset("dastyab doctor", "dastyab doctor"),
        QuickPreset("help", "help"),
        QuickPreset("clear", "clear")
    )

    fun normalizeInput(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return when {
            trimmed.startsWith("$ ") -> trimmed.removePrefix("$ ").trimStart()
            trimmed.startsWith("# ") -> trimmed.removePrefix("# ").trimStart()
            else -> trimmed
        }
    }

    fun classify(rawCommand: String?): Action {
        val cmd = normalizeInput(rawCommand)
        if (cmd.isEmpty()) return Action.EMPTY
        if (cmd == "clear" || cmd == "cls") return Action.CLEAR
        return Action.PYTHON_SHELL
    }

    fun formatPrompt(cwdDisplay: String?): String {
        val path = cwdDisplay?.takeIf { it.isNotBlank() } ?: "~"
        return "dastyab@android:$path$ "
    }

    fun buildVlessSyncJson(
        activeUri: String,
        layerUri: String,
        harvested: List<String>
    ): String {
        val merged = LinkedHashSet<String>()
        if (activeUri.trim().startsWith("vless://", ignoreCase = true)) merged.add(activeUri.trim())
        if (layerUri.trim().startsWith("vless://", ignoreCase = true)) merged.add(layerUri.trim())
        for (h in harvested) {
            val t = h.trim()
            if (t.startsWith("vless://", ignoreCase = true)) merged.add(t)
        }
        val arr = JSONArray()
        merged.take(150).forEach { arr.put(it) }
        return arr.toString()
    }

    fun buildRouteSyncJson(
        externalVpnActive: Boolean,
        dastyabVpnActive: Boolean,
        bridgePort: Int?,
        upstreamScheme: TunnelConfig.ProxyScheme,
        upstreamHost: String,
        upstreamPort: Int
    ): String {
        val obj = JSONObject()
        obj.put("external_vpn_active", externalVpnActive)
        obj.put("dastyab_vpn_active", dastyabVpnActive)
        if (bridgePort != null && bridgePort > 0) {
            obj.put("bridge_port", bridgePort)
        }
        if (upstreamScheme != TunnelConfig.ProxyScheme.DIRECT && upstreamPort in 1..65535) {
            obj.put("upstream_scheme", upstreamScheme.name.lowercase())
            obj.put("upstream_host", TunnelConfig.sanitizeProxyHost(upstreamHost))
            obj.put("upstream_port", upstreamPort)
        }
        return obj.toString()
    }

    fun parseEventLine(line: String?): HostEvent? {
        val trimmed = line?.trim().orEmpty()
        val prefix = "[DASTYAB_EVENT] "
        if (!trimmed.startsWith(prefix)) return null
        val rest = trimmed.substring(prefix.length).trim()
        val sp = rest.indexOf(' ')
        if (sp <= 0) return HostEvent(rest, "{}")
        val kind = rest.substring(0, sp).trim()
        val payload = rest.substring(sp + 1).trim().ifEmpty { "{}" }
        return HostEvent(kind, payload)
    }

    fun parseCliArgsJson(json: String): List<String> {
        val arr = JSONArray(json)
        return List(arr.length()) { idx -> arr.optString(idx, "") }.filter { it.isNotEmpty() }
    }
}
