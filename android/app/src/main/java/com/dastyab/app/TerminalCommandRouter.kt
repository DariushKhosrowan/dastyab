package com.dastyab.app

/**
 * TerminalCommandRouter — pure-Kotlin command normalization, prompt formatting,
 * profile node serialization, and IPC event parsing for Terminal Mode.
 *
 * Intentionally contains zero Android/JNI dependencies (including no `org.json.*`
 * Android stubs) so it is 100% testable on the plain JVM (`testDebugUnitTest`).
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
        QuickPreset("pip list", "pip list"),
        QuickPreset("pip --recommended", "pip install --recommended"),
        QuickPreset("pip check", "pip check"),
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
        return merged.take(150).joinToString(prefix = "[", postfix = "]", separator = ",") {
            "\"${escapeJson(it)}\""
        }
    }

    fun buildRouteSyncJson(
        externalVpnActive: Boolean,
        dastyabVpnActive: Boolean,
        bridgePort: Int?,
        upstreamScheme: TunnelConfig.ProxyScheme,
        upstreamHost: String,
        upstreamPort: Int
    ): String {
        val fields = mutableListOf(
            "\"external_vpn_active\":$externalVpnActive",
            "\"dastyab_vpn_active\":$dastyabVpnActive"
        )
        if (bridgePort != null && bridgePort > 0) {
            fields.add("\"bridge_port\":$bridgePort")
        }
        if (upstreamScheme != TunnelConfig.ProxyScheme.DIRECT && upstreamPort in 1..65535) {
            val scheme = escapeJson(upstreamScheme.name.lowercase())
            val host = escapeJson(TunnelConfig.sanitizeProxyHost(upstreamHost))
            fields.add("\"upstream_scheme\":\"$scheme\"")
            fields.add("\"upstream_host\":\"$host\"")
            fields.add("\"upstream_port\":$upstreamPort")
        }
        return fields.joinToString(prefix = "{", postfix = "}", separator = ",")
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
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val out = mutableListOf<String>()
        var i = 1
        val end = trimmed.length - 1
        while (i < end) {
            while (i < end && (trimmed[i].isWhitespace() || trimmed[i] == ',')) i++
            if (i >= end) break
            if (trimmed[i] != '"') return emptyList()
            i++ // skip opening quote
            val sb = StringBuilder()
            var closed = false
            while (i < end) {
                val c = trimmed[i++]
                if (c == '"') {
                    closed = true
                    break
                }
                if (c == '\\') {
                    if (i >= end) return emptyList()
                    when (val esc = trimmed[i++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > end) return emptyList()
                            val hex = trimmed.substring(i, i + 4)
                            val code = hex.toIntOrNull(16) ?: return emptyList()
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            if (!closed) return emptyList()
            val item = sb.toString()
            if (item.isNotEmpty()) out.add(item)
        }
        return out
    }

    internal fun escapeJson(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
    }

    /** Formats a safe `pip install ...` command from user-entered package specs, or null if empty/invalid. */
    fun buildPipInstallCommand(rawPackages: String?): String? {
        val tokens = rawPackages
            .orEmpty()
            .split(',', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val pkgRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._\\-\\[\\]=<>!~]*$")
        if (tokens.any { !it.matches(pkgRegex) }) return null
        return "pip install " + tokens.joinToString(" ")
    }
}
