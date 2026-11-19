package com.dastyab.app

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * WorkerConfigStudio — Pure-Kotlin engine for Cloudflare Worker deployment,
 * ~/.dastyab/profiles/<id>/ state inspection/editing, VLESS config parsing &
 * Clean-IP rewriting (preserving SNI/Host), and client format exporting
 * (VLESS share link, v2rayN Xray JSON, sing-box JSON, Clash YAML).
 *
 * Intentionally contains zero `android.*` and zero `org.json.*` dependencies
 * so every rule is 100% unit-testable on the plain JVM.
 */
object WorkerConfigStudio {

    enum class ExportFormat(val cliName: String, val label: String) {
        SHARE_LINK("link", "VLESS Link"),
        V2RAYN("v2rayn", "v2rayN (Xray JSON)"),
        SINGBOX("singbox", "sing-box JSON"),
        CLASH("clash", "Clash YAML");

        companion object {
            fun fromName(raw: String?): ExportFormat {
                return when (raw?.trim()?.lowercase()) {
                    "v2rayn", "v2ray", "xray" -> V2RAYN
                    "singbox", "sing-box", "sing_box" -> SINGBOX
                    "clash", "clash-meta", "clashmeta" -> CLASH
                    else -> SHARE_LINK
                }
            }
        }
    }

    enum class WorkerPreset(val id: String, val label: String, val description: String) {
        STANDARD("standard", "Standard (Balanced)", "Default VLESS-WS worker on latest Cloudflare compat date"),
        GAMING("gaming", "Gaming (Low-Latency)", "Tuned for low-latency routes & tighter edge probing"),
        MAX_COMPAT("max-compat", "Max-Compat (Broadest)", "Legacy compatibility flags for older Cloudflare tiers");

        companion object {
            fun fromId(raw: String?): WorkerPreset =
                values().firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: STANDARD
        }
    }

    data class VlessConfig(
        val uuid: String,
        val address: String,
        val port: Int = 443,
        val security: String = "tls",
        val transport: String = "ws",
        val sni: String = "",
        val host: String = "",
        val path: String = "/",
        val fp: String = "chrome",
        val encryption: String = "none",
        val pbk: String = "",
        val sid: String = "",
        val spx: String = "",
        val flow: String = "",
        val headerType: String = "",
        val name: String = "cf-worker-edge"
    ) {
        fun canonicalPath(): String {
            val decoded = decodePercent(path).trim()
            return if (decoded.isEmpty()) "/" else decoded
        }

        fun effectiveSni(): String = sni.trim().ifEmpty { host.trim().ifEmpty { address.trim() } }

        fun effectiveHost(): String = host.trim().ifEmpty { sni.trim().ifEmpty { address.trim() } }

        fun effectiveFingerprint(): String = fp.trim().ifEmpty { "chrome" }

        /**
         * Returns a new [VlessConfig] whose server address (and optional port) are replaced
         * with [cleanEndpoint] (`ip` or `ip:port`), while **preserving** the original worker
         * hostname inside `sni` and `host` so Cloudflare edge fronting continues to route.
         */
        fun applyCleanIp(cleanEndpoint: String, newName: String? = null): VlessConfig? {
            val parsed = parseCleanEndpoint(cleanEndpoint) ?: return null
            val preservedSni = effectiveSni()
            val preservedHost = effectiveHost()
            val targetName = newName?.trim()?.takeIf { it.isNotEmpty() }
                ?: "clean-${parsed.first}"
            return copy(
                address = parsed.first,
                port = parsed.second ?: port,
                sni = preservedSni,
                host = preservedHost,
                name = targetName
            )
        }

        fun toVlessUri(): String {
            val cleanUuid = uuid.trim()
            val cleanAddr = address.trim()
            val cleanPort = if (port in 1..65535) port else 443
            val sec = security.trim().ifEmpty { "tls" }
            val net = transport.trim().ifEmpty { "ws" }
            val enc = encryption.trim().ifEmpty { "none" }
            val sniVal = effectiveSni()
            val hostVal = effectiveHost()
            val pathVal = canonicalPath()
            val fpVal = effectiveFingerprint()
            val remark = name.trim().ifEmpty { "cf-worker-edge" }

            val q = mutableListOf(
                "encryption=${encodeQueryComponent(enc)}",
                "security=${encodeQueryComponent(sec)}",
                "type=${encodeQueryComponent(net)}",
                "sni=${encodeQueryComponent(sniVal)}",
                "host=${encodeQueryComponent(hostVal)}",
                // Keep plain '/' in path so v2rayN / v2rayNG never rejects "%2F"
                "path=$pathVal",
                "fp=${encodeQueryComponent(fpVal)}"
            )
            if (pbk.isNotBlank()) q.add("pbk=${encodeQueryComponent(pbk.trim())}")
            if (sid.isNotBlank()) q.add("sid=${encodeQueryComponent(sid.trim())}")
            if (spx.isNotBlank()) q.add("spx=$spx")
            if (flow.isNotBlank()) q.add("flow=${encodeQueryComponent(flow.trim())}")
            if (headerType.isNotBlank()) q.add("headerType=${encodeQueryComponent(headerType.trim())}")

            return "vless://$cleanUuid@$cleanAddr:$cleanPort?${q.joinToString("&")}#${encodeFragment(remark)}"
        }

        fun renderV2rayNJson(socksPort: Int = 10808): String {
            val sniVal = effectiveSni()
            val hostVal = effectiveHost()
            val pathVal = canonicalPath()
            val sec = security.trim().ifEmpty { "tls" }
            val net = transport.trim().ifEmpty { "ws" }
            val fpVal = effectiveFingerprint()
            val cleanPort = if (port in 1..65535) port else 443

            val stream = linkedMapOf<String, Any?>(
                "network" to net,
                "security" to sec
            )
            when (sec) {
                "tls" -> stream["tlsSettings"] = linkedMapOf(
                    "serverName" to sniVal,
                    "fingerprint" to fpVal
                )
                "reality" -> stream["realitySettings"] = linkedMapOf(
                    "serverName" to sniVal,
                    "fingerprint" to fpVal,
                    "publicKey" to pbk.trim(),
                    "shortId" to sid.trim(),
                    "spiderX" to spx.trim()
                )
            }
            when (net) {
                "ws" -> stream["wsSettings"] = linkedMapOf(
                    "path" to pathVal,
                    "headers" to linkedMapOf("Host" to hostVal)
                )
                "xhttp" -> stream["xhttpSettings"] = linkedMapOf(
                    "path" to pathVal,
                    "host" to hostVal
                )
                "grpc" -> stream["grpcSettings"] = linkedMapOf(
                    "serviceName" to pathVal
                )
                else -> stream["tcpSettings"] = linkedMapOf(
                    "header" to linkedMapOf("type" to headerType.trim().ifEmpty { "none" })
                )
            }

            val userObj = linkedMapOf<String, Any?>(
                "id" to uuid.trim(),
                "encryption" to encryption.trim().ifEmpty { "none" }
            )
            if (flow.isNotBlank()) userObj["flow"] = flow.trim()

            val root = linkedMapOf<String, Any?>(
                "remarks" to name.trim().ifEmpty { "cf-worker-edge" },
                "log" to linkedMapOf("loglevel" to "warning"),
                "inbounds" to listOf(
                    linkedMapOf(
                        "tag" to "socks-in",
                        "listen" to "127.0.0.1",
                        "port" to socksPort,
                        "protocol" to "socks",
                        "settings" to linkedMapOf("auth" to "noauth", "udp" to true)
                    )
                ),
                "outbounds" to listOf(
                    linkedMapOf(
                        "protocol" to "vless",
                        "settings" to linkedMapOf(
                            "vnext" to listOf(
                                linkedMapOf(
                                    "address" to address.trim(),
                                    "port" to cleanPort,
                                    "users" to listOf(userObj)
                                )
                            )
                        ),
                        "streamSettings" to stream
                    )
                )
            )
            return toPrettyJson(root)
        }

        fun renderSingBoxJson(mixedPort: Int = 2080): String {
            val sniVal = effectiveSni()
            val hostVal = effectiveHost()
            val pathVal = canonicalPath()
            val sec = security.trim().ifEmpty { "tls" }
            val net = transport.trim().ifEmpty { "ws" }
            val fpVal = effectiveFingerprint()
            val cleanPort = if (port in 1..65535) port else 443

            val outbound = linkedMapOf<String, Any?>(
                "type" to "vless",
                "tag" to "dastyab",
                "server" to address.trim(),
                "server_port" to cleanPort,
                "uuid" to uuid.trim(),
                "packet_encoding" to "xudp",
                "tls" to linkedMapOf(
                    "enabled" to (sec == "tls" || sec == "reality"),
                    "server_name" to sniVal,
                    "utls" to linkedMapOf("enabled" to true, "fingerprint" to fpVal)
                ),
                "transport" to linkedMapOf(
                    "type" to net,
                    "path" to pathVal,
                    "headers" to linkedMapOf("Host" to hostVal)
                )
            )
            val root = linkedMapOf<String, Any?>(
                "log" to linkedMapOf("level" to "warn"),
                "inbounds" to listOf(
                    linkedMapOf(
                        "type" to "mixed",
                        "tag" to "mixed-in",
                        "listen" to "127.0.0.1",
                        "listen_port" to mixedPort
                    )
                ),
                "outbounds" to listOf(outbound),
                "route" to linkedMapOf("final" to "dastyab")
            )
            return toPrettyJson(root)
        }

        fun renderClashYaml(): String {
            val sniVal = effectiveSni()
            val hostVal = effectiveHost()
            val pathVal = canonicalPath()
            val tlsEnabled = security.trim().equals("tls", ignoreCase = true)
            val net = transport.trim().ifEmpty { "ws" }
            val cleanPort = if (port in 1..65535) port else 443
            val safeName = sanitizeYamlValue(name.trim().ifEmpty { "cf-worker-edge" })
            return buildString {
                append("- name: ").append(safeName).append('\n')
                append("  type: vless\n")
                append("  server: ").append(address.trim()).append('\n')
                append("  port: ").append(cleanPort).append('\n')
                append("  uuid: ").append(uuid.trim()).append('\n')
                append("  network: ").append(net).append('\n')
                append("  tls: ").append(tlsEnabled).append('\n')
                append("  servername: ").append(sniVal).append('\n')
                append("  client-fingerprint: ").append(effectiveFingerprint()).append('\n')
                append("  ws-opts:\n")
                append("    path: ").append(pathVal).append('\n')
                append("    headers:\n")
                append("      Host: ").append(hostVal).append('\n')
            }
        }

        fun export(format: ExportFormat): String = when (format) {
            ExportFormat.SHARE_LINK -> toVlessUri()
            ExportFormat.V2RAYN -> renderV2rayNJson()
            ExportFormat.SINGBOX -> renderSingBoxJson()
            ExportFormat.CLASH -> renderClashYaml()
        }
    }

    data class ProfileMeta(
        val id: String,
        val name: String,
        val createdAt: String = "",
        val description: String = ""
    )

    data class TokenSummary(
        val label: String,
        val maskedToken: String,
        val ts: String,
        val isActive: Boolean
    )

    data class AccountSummary(
        val id: String,
        val name: String,
        val subdomain: String
    ) {
        fun workersDevSuffix(): String =
            if (subdomain.isNotBlank()) "$subdomain.workers.dev" else "(subdomain unknown)"
    }

    data class SavedConfigRecord(
        val id: String,
        val ts: String,
        val uuid: String,
        val address: String,
        val port: Int,
        val name: String,
        val isCleanIp: Boolean,
        val link: String,
        val note: String
    )

    data class ProfileSummary(
        val profileId: String,
        val profileName: String,
        val activeTokenLabel: String?,
        val tokens: List<TokenSummary>,
        val accounts: List<AccountSummary>,
        val activeAccountId: String,
        val lastDeployedBaseLink: String,
        val cleanIps: List<String>,
        val history: List<SavedConfigRecord>
    )

    // -------------------------------------------------------------------------
    // VLESS Parsing & Custom Worker Synthesis
    // -------------------------------------------------------------------------

    fun parseVless(raw: String?): VlessConfig? {
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) return null
        val body = trimmed.substring("vless://".length)

        val fragIdx = body.indexOf('#')
        val noFrag = if (fragIdx >= 0) body.substring(0, fragIdx) else body
        val rawFrag = if (fragIdx >= 0) body.substring(fragIdx + 1) else ""

        val qIdx = noFrag.indexOf('?')
        val userHost = if (qIdx >= 0) noFrag.substring(0, qIdx) else noFrag
        val rawQuery = if (qIdx >= 0) noFrag.substring(qIdx + 1) else ""

        val atIdx = userHost.lastIndexOf('@')
        if (atIdx <= 0) return null
        val uuid = userHost.substring(0, atIdx).trim()
        val hostPort = userHost.substring(atIdx + 1).trim()
        if (uuid.isEmpty() || hostPort.isEmpty()) return null

        val (address, port) = splitHostPort(hostPort) ?: return null
        if (address.isEmpty()) return null

        val params = LinkedHashMap<String, String>()
        if (rawQuery.isNotEmpty()) {
            for (pair in rawQuery.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = pair.substring(0, eq).trim().lowercase()
                    val valRaw = pair.substring(eq + 1)
                    params[key] = decodePercent(valRaw)
                }
            }
        }

        val security = params["security"]?.trim()?.takeIf { it.isNotEmpty() } ?: "tls"
        val transport = params["type"]?.trim()?.takeIf { it.isNotEmpty() } ?: "ws"
        val sni = params["sni"]?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        val host = params["host"]?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        val path = params["path"]?.trim()?.takeIf { it.isNotEmpty() } ?: "/"
        val fp = params["fp"]?.trim()?.takeIf { it.isNotEmpty() } ?: "chrome"
        val encryption = params["encryption"]?.trim()?.takeIf { it.isNotEmpty() } ?: "none"
        val pbk = params["pbk"]?.trim().orEmpty()
        val sid = params["sid"]?.trim().orEmpty()
        val spx = params["spx"]?.trim().orEmpty()
        val flow = params["flow"]?.trim().orEmpty()
        val headerType = params["headertype"]?.trim().orEmpty()
        val name = if (rawFrag.isNotEmpty()) decodePercent(rawFrag).trim() else "cf-worker-edge"

        return VlessConfig(
            uuid = uuid,
            address = address,
            port = port,
            security = security,
            transport = transport,
            sni = sni.ifEmpty { host.ifEmpty { address } },
            host = host.ifEmpty { sni.ifEmpty { address } },
            path = path,
            fp = fp,
            encryption = encryption,
            pbk = pbk,
            sid = sid,
            spx = spx,
            flow = flow,
            headerType = headerType,
            name = name.ifEmpty { "cf-worker-edge" }
        )
    }

    /**
     * Synthesizes a [VlessConfig] when the user supplies an existing Cloudflare Worker
     * hostname (e.g. `myworker.sub.workers.dev` or a custom domain) and optional UUID,
     * WS path, port, and Clean IP.
     */
    fun fromExistingWorker(
        workerHostOrUrl: String,
        uuidInput: String = "",
        wsPath: String = "/",
        port: Int = 443,
        cleanIpEndpoint: String? = null,
        label: String? = null
    ): VlessConfig? {
        val workerHost = normalizeWorkerHost(workerHostOrUrl) ?: return null
        val resolvedUuid = uuidInput.trim().ifEmpty { UUID.randomUUID().toString() }
        if (resolvedUuid.any { it.isWhitespace() || it == '@' }) return null

        val cleanPath = wsPath.trim().let {
            if (it.isEmpty()) "/" else if (it.startsWith("/")) it else "/$it"
        }
        val cleanPort = if (port in 1..65535) port else 443
        val baseName = label?.trim()?.takeIf { it.isNotEmpty() } ?: workerHost

        val base = VlessConfig(
            uuid = resolvedUuid,
            address = workerHost,
            port = cleanPort,
            security = "tls",
            transport = "ws",
            sni = workerHost,
            host = workerHost,
            path = cleanPath,
            fp = "chrome",
            encryption = "none",
            name = baseName
        )
        val cleanEp = cleanIpEndpoint?.trim().orEmpty()
        if (cleanEp.isEmpty()) return base
        return base.applyCleanIp(cleanEp, label?.trim()?.takeIf { it.isNotEmpty() }) ?: base
    }

    fun normalizeWorkerHost(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("https://", ignoreCase = true)) s = s.substring(8)
        else if (s.startsWith("http://", ignoreCase = true)) s = s.substring(7)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        val colonIdx = s.lastIndexOf(':')
        if (colonIdx > 0 && !s.startsWith("[")) {
            s = s.substring(0, colonIdx).trim()
        }
        if (s.isEmpty() || s.any { it.isWhitespace() || it == '@' }) return null
        return s
    }

    // -------------------------------------------------------------------------
    // Clean IP Validation & Parsing
    // -------------------------------------------------------------------------

    /**
     * Validates a canonical IPv4 Clean IP (`a.b.c.d`) without leading-zero octets.
     */
    fun isValidCanonicalIpv4(ip: String?): Boolean {
        val s = ip?.trim().orEmpty()
        val parts = s.split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            if (p.isEmpty() || !p.all { it in '0'..'9' }) return false
            if (p.length > 1 && p.startsWith('0')) return false
            val v = p.toIntOrNull() ?: return false
            if (v !in 0..255) return false
        }
        return true
    }

    /**
     * Parses `ip` or `ip:port` into `(canonicalIp, optionalPort)`.
     * Returns null if IPv4 or port is invalid.
     */
    fun parseCleanEndpoint(raw: String?): Pair<String, Int?>? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val colonIdx = s.lastIndexOf(':')
        return if (colonIdx > 0) {
            val ip = s.substring(0, colonIdx).trim()
            val portStr = s.substring(colonIdx + 1).trim()
            val port = portStr.toIntOrNull() ?: return null
            if (!isValidCanonicalIpv4(ip) || port !in 1..65535) return null
            ip to port
        } else {
            if (!isValidCanonicalIpv4(s)) return null
            s to null
        }
    }

    fun canonicalCleanEndpoint(raw: String?): String? {
        val parsed = parseCleanEndpoint(raw) ?: return null
        return if (parsed.second != null) "${parsed.first}:${parsed.second}" else parsed.first
    }

    fun extractCleanIpsFromText(raw: String?): List<String> {
        val text = raw.orEmpty()
        if (text.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        for (token in text.split(',', ';', '\n', '\r', '\t', ' ')) {
            val clean = canonicalCleanEndpoint(token)
            if (clean != null) out.add(clean)
        }
        return out.toList()
    }

    // -------------------------------------------------------------------------
    // Native CLI (`libdastyab.so`) Argument Builders
    // -------------------------------------------------------------------------

    private fun withProfile(profileId: String, rest: List<String>): List<String> {
        val p = sanitizeProfileId(profileId)
        return if (p == "default") rest else listOf("--profile", p) + rest
    }

    fun sanitizeProfileId(profileId: String?): String {
        val s = profileId?.trim().orEmpty()
        if (s.isEmpty() || s.any { it.isWhitespace() || it == '/' || it == '\\' }) return "default"
        return s
    }

    fun buildTokenRecoverArgs(profileId: String, label: String, token: String): List<String>? {
        val cleanTok = token.trim()
        if (cleanTok.isEmpty()) return null
        val cleanLabel = label.trim().ifEmpty { "default" }
        return withProfile(profileId, listOf("token-recover", cleanLabel, cleanTok))
    }

    fun buildTokenAddArgs(profileId: String, label: String, token: String): List<String>? {
        val cleanTok = token.trim()
        if (cleanTok.isEmpty()) return null
        val cleanLabel = label.trim().ifEmpty { "default" }
        return withProfile(profileId, listOf("token-add", cleanLabel, cleanTok))
    }

    fun buildVerifyArgs(profileId: String): List<String> =
        withProfile(profileId, listOf("verify"))

    fun buildAccountsArgs(profileId: String): List<String> =
        withProfile(profileId, listOf("accounts"))

    fun buildDeployArgs(
        profileId: String,
        accountId: String? = null,
        workerName: String? = null,
        preset: WorkerPreset = WorkerPreset.STANDARD
    ): List<String> {
        val cmd = mutableListOf("deploy", "--preset", preset.id)
        val acc = accountId?.trim().orEmpty()
        if (acc.isNotEmpty()) {
            cmd.add("--account")
            cmd.add(acc)
        }
        val worker = workerName?.trim().orEmpty()
        if (worker.isNotEmpty()) {
            cmd.add("--worker")
            cmd.add(worker)
        }
        return withProfile(profileId, cmd)
    }

    fun buildFindArgs(
        profileId: String,
        count: Int = 3,
        seedIps: List<String> = emptyList(),
        sample: Int? = null,
        workers: Int? = null
    ): List<String> {
        val safeCount = count.coerceIn(1, 50)
        val cmd = mutableListOf("find", safeCount.toString())
        if (sample != null && sample in 50..20000) {
            cmd.add("--sample")
            cmd.add(sample.toString())
        }
        if (workers != null && workers in 1..256) {
            cmd.add("--workers")
            cmd.add(workers.toString())
        }
        val cleanSeeds = seedIps.mapNotNull { parseCleanEndpoint(it)?.first }.distinct()
        if (cleanSeeds.isNotEmpty()) {
            cmd.add("--seed")
            cmd.add(cleanSeeds.joinToString(","))
        }
        return withProfile(profileId, cmd)
    }

    fun buildExportArgs(profileId: String, format: ExportFormat): List<String> =
        withProfile(profileId, listOf("export", format.cliName))

    fun buildConfigsListArgs(profileId: String): List<String> =
        withProfile(profileId, listOf("configs", "list"))

    fun buildCleanipListArgs(profileId: String): List<String> =
        withProfile(profileId, listOf("cleanip", "list"))

    fun buildProfileCreateArgs(name: String, description: String = ""): List<String>? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val cmd = mutableListOf("profile-create", clean)
        if (description.isNotBlank()) {
            cmd.add("--description")
            cmd.add(description.trim())
        }
        return cmd
    }

    fun buildRoutesListArgs(profileId: String): List<String> =
        withProfile(profileId, listOf("routes", "list"))

    fun buildRoutesCreateArgs(profileId: String, pattern: String, script: String): List<String>? {
        val p = pattern.trim()
        val s = script.trim()
        if (p.isEmpty() || s.isEmpty()) return null
        return withProfile(profileId, listOf("routes", "create", p, s))
    }

    fun buildDnsListArgs(profileId: String, zoneIdOrDomain: String): List<String>? {
        val z = zoneIdOrDomain.trim()
        if (z.isEmpty()) return null
        return withProfile(profileId, listOf("dns", "list", z))
    }

    fun buildMultiWorkerDeployArgs(profileId: String, count: Int = 2): List<String> =
        withProfile(profileId, listOf("multi-worker", "deploy", "--count", count.coerceIn(1, 3).toString()))

    fun buildSimpleSubcommandArgs(profileId: String, vararg parts: String): List<String> =
        withProfile(profileId, parts.toList())

    // -------------------------------------------------------------------------
    // Shared `$HOME/.dastyab/profiles/<id>/` File Store Interop
    // -------------------------------------------------------------------------

    fun profilesDir(homeDir: File): File = File(File(homeDir, ".dastyab"), "profiles")

    fun profileDir(homeDir: File, profileId: String): File =
        File(profilesDir(homeDir), sanitizeProfileId(profileId))

    fun listProfiles(homeDir: File): List<ProfileMeta> {
        val dir = profilesDir(homeDir)
        val result = mutableListOf<ProfileMeta>()
        var hasDefault = false
        val children = dir.listFiles()
        if (children != null) {
            for (child in children) {
                if (!child.isDirectory) continue
                val metaFile = File(child, "profile.json")
                if (metaFile.isFile) {
                    val obj = readJsonObject(metaFile)
                    val id = (obj?.get("id") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: child.name
                    val name = (obj?.get("name") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: id
                    val created = (obj?.get("created_at") as? String).orEmpty()
                    val desc = (obj?.get("description") as? String).orEmpty()
                    if (id == "default") hasDefault = true
                    result.add(ProfileMeta(id = id, name = name, createdAt = created, description = desc))
                }
            }
        }
        if (!hasDefault) {
            result.add(0, ProfileMeta(id = "default", name = "Default", description = "Default profile"))
        }
        return result.sortedWith(compareBy<ProfileMeta> { it.id != "default" }.thenBy { it.name.lowercase() })
    }

    fun loadProfileSummary(homeDir: File, profileId: String): ProfileSummary {
        val pid = sanitizeProfileId(profileId)
        val dir = profileDir(homeDir, pid)

        val metaObj = readJsonObject(File(dir, "profile.json"))
        val profileName = (metaObj?.get("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (pid == "default") "Default" else pid

        val activeTokenRaw = File(dir, "active_token").takeIf { it.isFile }?.let {
            runCatching { parseJson(it.readText()) as? String }.getOrNull()
        }?.trim()?.takeIf { it.isNotEmpty() }

        val tokensMap = readJsonObject(File(dir, "token.json")) ?: emptyMap()
        val tokens = tokensMap.entries.map { (label, v) ->
            val entryObj = v as? Map<*, *>
            val rawTok = (entryObj?.get("token") as? String).orEmpty()
            val ts = (entryObj?.get("ts") as? String).orEmpty()
            TokenSummary(
                label = label,
                maskedToken = maskToken(rawTok),
                ts = ts,
                isActive = label == activeTokenRaw
            )
        }.sortedWith(compareBy<TokenSummary> { !it.isActive }.thenBy { it.label })

        val accountsMap = readJsonObject(File(dir, "accounts.json")) ?: emptyMap()
        val accounts = accountsMap.entries.map { (accId, v) ->
            val obj = v as? Map<*, *>
            AccountSummary(
                id = accId,
                name = (obj?.get("name") as? String).orEmpty(),
                subdomain = (obj?.get("subdomain") as? String).orEmpty()
            )
        }

        val configObj = readJsonObject(File(dir, "config.json")) ?: emptyMap()
        val activeAccount = (configObj["active_account"] as? String).orEmpty()
        val lastDeployedBase = (configObj["last_deployed_base_link"] as? String).orEmpty()

        val cleanIpsRaw = readJsonArray(File(dir, "cleanips.json")) ?: emptyList<Any?>()
        val cleanIps = LinkedHashSet<String>()
        for (item in cleanIpsRaw) {
            canonicalCleanEndpoint(item as? String)?.let { cleanIps.add(it) }
        }

        val historyArr = readJsonArray(File(dir, "history.json")) ?: emptyList<Any?>()
        val history = historyArr.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val link = (obj["link"] as? String).orEmpty()
            val address = (obj["address"] as? String).orEmpty()
            val portNum = (obj["port"] as? Number)?.toInt() ?: 443
            SavedConfigRecord(
                id = (obj["id"] as? String).orEmpty(),
                ts = (obj["ts"] as? String).orEmpty(),
                uuid = (obj["uuid"] as? String).orEmpty(),
                address = address,
                port = portNum,
                name = (obj["name"] as? String).orEmpty(),
                isCleanIp = (obj["clean_ip"] as? Boolean) ?: false,
                link = link,
                note = (obj["note"] as? String).orEmpty()
            )
        }

        return ProfileSummary(
            profileId = pid,
            profileName = profileName,
            activeTokenLabel = activeTokenRaw ?: tokens.firstOrNull()?.label,
            tokens = tokens,
            accounts = accounts,
            activeAccountId = activeAccount,
            lastDeployedBaseLink = lastDeployedBase,
            cleanIps = cleanIps.toList(),
            history = history
        )
    }

    fun saveTokenToProfile(
        homeDir: File,
        profileId: String,
        label: String,
        token: String,
        makeActive: Boolean = true
    ): Boolean {
        val cleanTok = token.trim()
        if (cleanTok.isEmpty()) return false
        val cleanLabel = label.trim().ifEmpty { "default" }
        val dir = ensureProfileDir(homeDir, profileId)
        val tokenFile = File(dir, "token.json")
        val current = LinkedHashMap<String, Any?>()
        readJsonObject(tokenFile)?.let { current.putAll(it) }
        current[cleanLabel] = linkedMapOf(
            "token" to cleanTok,
            "ts" to nowIsoUtc()
        )
        writeJsonAtomic(tokenFile, current)
        if (makeActive) {
            writeJsonAtomic(File(dir, "active_token"), cleanLabel)
        }
        return true
    }

    fun appendCleanIps(homeDir: File, profileId: String, endpoints: List<String>): List<String> {
        val dir = ensureProfileDir(homeDir, profileId)
        val file = File(dir, "cleanips.json")
        val merged = LinkedHashSet<String>()
        readJsonArray(file)?.forEach { item ->
            canonicalCleanEndpoint(item as? String)?.let { merged.add(it) }
        }
        for (ep in endpoints) {
            canonicalCleanEndpoint(ep)?.let { merged.add(it) }
        }
        val list = merged.toList()
        writeJsonAtomic(file, list)
        return list
    }

    fun removeCleanIp(homeDir: File, profileId: String, endpoint: String): List<String> {
        val dir = ensureProfileDir(homeDir, profileId)
        val file = File(dir, "cleanips.json")
        val target = canonicalCleanEndpoint(endpoint) ?: endpoint.trim()
        val list = mutableListOf<String>()
        readJsonArray(file)?.forEach { item ->
            val ep = canonicalCleanEndpoint(item as? String)
            if (ep != null && ep != target) list.add(ep)
        }
        writeJsonAtomic(file, list)
        return list
    }

    fun appendHistoryRecord(
        homeDir: File,
        profileId: String,
        config: VlessConfig,
        isCleanIp: Boolean,
        note: String = "android-studio"
    ): SavedConfigRecord {
        val dir = ensureProfileDir(homeDir, profileId)
        val historyFile = File(dir, "history.json")
        val existing = mutableListOf<Any?>()
        readJsonArray(historyFile)?.let { existing.addAll(it) }

        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val ts = nowIsoUtc()
        val epochSec = System.currentTimeMillis() / 1000.0
        val link = config.toVlessUri()

        val entryMap = linkedMapOf<String, Any?>(
            "id" to id,
            "ts" to ts,
            "epoch" to epochSec,
            "uuid" to config.uuid.trim(),
            "address" to config.address.trim(),
            "port" to config.port,
            "name" to config.name.trim(),
            "clean_ip" to isCleanIp,
            "link" to link,
            "note" to note
        )
        val updated = mutableListOf<Any?>()
        updated.add(entryMap)
        for (item in existing) {
            val m = item as? Map<*, *>
            val oldLink = m?.get("link") as? String
            if (oldLink != link) updated.add(item)
            if (updated.size >= 500) break
        }
        writeJsonAtomic(historyFile, updated)

        // Ensure config.json records last_deployed_base_link if not set or when saving a base worker link
        val cfgFile = File(dir, "config.json")
        val cfgObj = LinkedHashMap<String, Any?>()
        readJsonObject(cfgFile)?.let { cfgObj.putAll(it) }
        if (!isCleanIp || (cfgObj["last_deployed_base_link"] as? String).isNullOrBlank()) {
            cfgObj["last_deployed_base_link"] = link
            writeJsonAtomic(cfgFile, cfgObj)
        }

        return SavedConfigRecord(
            id = id,
            ts = ts,
            uuid = config.uuid.trim(),
            address = config.address.trim(),
            port = config.port,
            name = config.name.trim(),
            isCleanIp = isCleanIp,
            link = link,
            note = note
        )
    }

    fun maskToken(token: String): String {
        val t = token.trim()
        if (t.isEmpty()) return "(empty)"
        if (t.length <= 10) return "****"
        return "${t.take(4)}…${t.takeLast(4)}"
    }

    private fun ensureProfileDir(homeDir: File, profileId: String): File {
        val pid = sanitizeProfileId(profileId)
        val dir = profileDir(homeDir, pid)
        if (!dir.exists()) dir.mkdirs()
        val metaFile = File(dir, "profile.json")
        if (!metaFile.isFile) {
            writeJsonAtomic(
                metaFile,
                linkedMapOf(
                    "id" to pid,
                    "name" to if (pid == "default") "Default" else pid,
                    "created_at" to nowIsoUtc(),
                    "description" to "DastYab profile"
                )
            )
        }
        return dir
    }

    private fun nowIsoUtc(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun writeJsonAtomic(file: File, value: Any?) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(toPrettyJson(value) + "\n", Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(toPrettyJson(value) + "\n", Charsets.UTF_8)
            tmp.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Internal Pure-Kotlin URL & RFC-8259 JSON Helpers (No org.json stubs!)
    // -------------------------------------------------------------------------

    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        if (hostPort.startsWith("[")) {
            val closeIdx = hostPort.indexOf(']')
            if (closeIdx <= 1) return null
            val host = hostPort.substring(1, closeIdx)
            val rest = hostPort.substring(closeIdx + 1)
            val port = if (rest.startsWith(":")) {
                rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
            } else 443
            return host to port
        }
        val colonIdx = hostPort.lastIndexOf(':')
        return if (colonIdx > 0) {
            val host = hostPort.substring(0, colonIdx).trim()
            val port = hostPort.substring(colonIdx + 1).trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
            host to port
        } else {
            hostPort.trim() to 443
        }
    }

    private fun decodePercent(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun encodeQueryComponent(value: String): String = try {
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        value
    }

    private fun encodeFragment(value: String): String = try {
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        value
    }

    private fun sanitizeYamlValue(name: String): String = buildString(name.length) {
        for (ch in name) {
            when (ch) {
                ':', '\n', '\r', '\t', '[', ']', '{', '}', '#' -> append('-')
                else -> append(ch)
            }
        }
    }

    private fun readJsonObject(file: File): Map<String, Any?>? {
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return runCatching { parseJson(text) as? Map<String, Any?> }.getOrNull()
    }

    private fun readJsonArray(file: File): List<Any?>? {
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return runCatching { parseJson(text) as? List<Any?> }.getOrNull()
    }

    fun toPrettyJson(value: Any?, indent: Int = 0): String {
        val pad = "  ".repeat(indent)
        val childPad = "  ".repeat(indent + 1)
        return when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Number -> {
                val d = value.toDouble()
                if (value is Int || value is Long || (d % 1.0 == 0.0 && d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble())) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
            }
            is String -> "\"${escapeJsonString(value)}\""
            is Map<*, *> -> {
                if (value.isEmpty()) "{}" else buildString {
                    append("{\n")
                    val entries = value.entries.toList()
                    entries.forEachIndexed { idx, (k, v) ->
                        append(childPad)
                        append('"').append(escapeJsonString(k.toString())).append("\": ")
                        append(toPrettyJson(v, indent + 1))
                        if (idx < entries.lastIndex) append(',')
                        append('\n')
                    }
                    append(pad).append('}')
                }
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) "[]" else buildString {
                    append("[\n")
                    items.forEachIndexed { idx, item ->
                        append(childPad)
                        append(toPrettyJson(item, indent + 1))
                        if (idx < items.lastIndex) append(',')
                        append('\n')
                    }
                    append(pad).append(']')
                }
            }
            else -> "\"${escapeJsonString(value.toString())}\""
        }
    }

    private fun escapeJsonString(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    fun parseJson(raw: String): Any? {
        val p = JsonParser(raw)
        val result = p.parseValue()
        p.skipWhitespace()
        return result
    }

    private class JsonParser(private val s: String) {
        private var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val ch = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char '$ch' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (pos < s.length) {
                skipWhitespace()
                if (s[pos] != '"') throw IllegalArgumentException("Expected string key at $pos")
                val key = parseString()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') throw IllegalArgumentException("Expected ':' at $pos")
                pos++
                map[key] = parseValue()
                skipWhitespace()
                if (pos >= s.length) throw IllegalArgumentException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
            throw IllegalArgumentException("Unterminated object")
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (pos < s.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos >= s.length) throw IllegalArgumentException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
            throw IllegalArgumentException("Unterminated array")
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (pos >= s.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = s[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) throw IllegalArgumentException("Bad unicode escape")
                            val code = s.substring(pos, pos + 4).toIntOrNull(16)
                                ?: throw IllegalArgumentException("Bad hex unicode")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            var isFloat = false
            while (pos < s.length) {
                val c = s[pos]
                if (c in '0'..'9') {
                    pos++
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if (pos == start && c == '-') { pos++; continue }
                    if (c == '-' || c == '+') {
                        val prev = s.getOrNull(pos - 1)
                        if (prev != 'e' && prev != 'E') break
                    }
                    isFloat = true
                    pos++
                } else {
                    break
                }
            }
            val numStr = s.substring(start, pos)
            return if (isFloat) {
                numStr.toDouble()
            } else {
                numStr.toLongOrNull() ?: numStr.toDouble()
            }
        }

        private fun <T> expectLiteral(lit: String, value: T): T {
            if (!s.regionMatches(pos, lit, 0, lit.length)) {
                throw IllegalArgumentException("Expected '$lit' at $pos")
            }
            pos += lit.length
            return value
        }
    }
}
