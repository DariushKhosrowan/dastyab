package com.dastyab.app

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * UniversalConfig — pure-Kotlin multi-protocol config extractor, parser, and WireGuard INI codec.
 *
 * Supports:
 *  • Xray / Sing-box URIs: `vless://`, `vmess://`, `trojan://`, `ss://`, `ssr://`,
 *    `hy2://`, `hysteria2://`, `hysteria://`, `tuic://`, `juicity://`, `anytls://`,
 *    `naive+https://`, `naive+quic://`, `naive://`
 *  • WireGuard / WARP / AmneziaWG: `wireguard://`, `wg://`, `warp://`, `amneziawg://`,
 *    PLUS raw INI `[Interface] ... [Peer]` `.conf` text blocks (with lossless round-trip conversion)
 *  • NapsternetV: `npv://`, `npv4://`, `napsternetv://` (both URI and Base64 JSON forms)
 *  • Proxy / Tunnel / Stealth: `socks5://`, `socks4://`, `socks://`, `ssh://`, `sn://`, `dnstt://`, `slipnet://`
 *  • Telegram MTProto proxies: `tg://proxy?server=...&port=...&secret=...` and `https://t.me/proxy?...`
 *
 * Pure Kotlin (no `org.json` Android stubs) so all extraction, WireGuard INI conversion,
 * Base64 decoding, and host/port parsing run identically on Android and plain JVM unit tests.
 */
object UniversalConfig {

    enum class Family(val displayName: String) {
        XRAY_SINGBOX("Xray / Sing-box"),
        WIREGUARD("WireGuard / WARP"),
        OPENVPN("OpenVPN"),
        NAPSTERNETV("NapsternetV"),
        PROXY_TUNNEL("SOCKS / SSH / Stealth"),
        TELEGRAM_MTPROTO("Telegram MTProto")
    }

    data class ParsedEndpoint(
        val uri: String,
        val protocol: String,
        val family: Family,
        val host: String,
        val port: Int,
        val label: String
    ) {
        fun summary(): String {
            val tag = if (label.isNotBlank()) "$label · " else ""
            return "$tag$host:$port (${protocol.uppercase()})"
        }
    }

    private val SUPPORTED_PROTOCOLS = setOf(
        "vless", "vmess", "trojan", "ss", "ssr",
        "hysteria2", "hysteria", "hy2", "tuic",
        "juicity", "anytls", "naive+https", "naive+quic", "naive",
        "wireguard", "wg", "warp", "amneziawg",
        "openvpn", "ovpn",
        "npv4", "npvs", "npvt", "npv", "napsternetv",
        "socks5", "socks4", "socks", "ssh",
        "sn", "dnstt", "slipnet",
        "mtproto"
    )

    private val SUPPORTED_SCHEME_PATTERN = listOf(
        "vless", "vmess", "trojan", "ss", "ssr",
        "hysteria2", "hysteria", "hy2", "tuic",
        "juicity", "anytls", "naive\\+https", "naive\\+quic", "naive",
        "wireguard", "wg", "warp", "amneziawg",
        "openvpn", "ovpn",
        "npv4", "npvs", "npvt", "npv", "napsternetv",
        "socks5", "socks4", "socks", "ssh",
        "sn", "dnstt", "slipnet"
    ).joinToString("|")

    private val URI_REGEX = Regex(
        """(?<![A-Za-z0-9+.-])(?:$SUPPORTED_SCHEME_PATTERN)://[^\s"'<>`\\]+""",
        RegexOption.IGNORE_CASE
    )

    private val MTPROTO_URI_REGEX = Regex(
        """(?<![A-Za-z0-9+./-])(?:tg://proxy\?[^\s"'<>`\\]+|https?://(?:t\.me|telegram\.me)/proxy\?[^\s"'<>`\\]+)""",
        RegexOption.IGNORE_CASE
    )

    private val TG_SOCKS_URI_REGEX = Regex(
        """(?<![A-Za-z0-9+./-])(?:tg://socks\?[^\s"'<>`\\]+|https?://(?:t\.me|telegram\.me)/socks\?[^\s"'<>`\\]+)""",
        RegexOption.IGNORE_CASE
    )

    private val B64_TOKEN_REGEX = Regex("""[A-Za-z0-9+/_=-]{32,}""")

    /** Extracts every supported config URI (including WireGuard INI blocks, MTProto links, and nested Base64 blobs). */
    fun extractAll(rawPayload: String, maxConfigs: Int = 250): List<String> {
        if (rawPayload.isBlank()) return emptyList()
        val unescaped = unescapeHtml(rawPayload)
        val found = LinkedHashSet<String>()

        collectDirectMatches(unescaped, found)

        // Decode entire payload if it looks like a pure Base64 subscription
        val fullDecoded = tryDecodeBase64(unescaped)
        if (fullDecoded != null) {
            collectDirectMatches(fullDecoded, found)
        }

        // Also scan standalone Base64 blobs embedded inside Telegram messages or mixed subscriptions
        var decodedChunks = 0
        for (match in B64_TOKEN_REGEX.findAll(unescaped)) {
            if (decodedChunks >= 24) break
            val token = match.value
            // Skip tokens that are immediately part of a vmess:// / ssr:// / npv4:// URI already captured
            val start = match.range.first
            val prefixWindow = unescaped.substring((start - 14).coerceAtLeast(0), start)
            if (prefixWindow.contains("://")) continue

            val decoded = tryDecodeBase64(token) ?: continue
            if (decoded.contains("://") || decoded.contains("[Interface]", ignoreCase = true)) {
                decodedChunks++
                collectDirectMatches(decoded, found)
            }
        }

        return found
            .sortedWith(compareBy<String> { priorityRank(it) }.thenBy { it.length })
            .take(maxConfigs)
    }

    private fun collectDirectMatches(text: String, out: LinkedHashSet<String>) {
        URI_REGEX.findAll(text).forEach { m ->
            val cleaned = cleanUri(m.value)
            if (isPlausible(cleaned)) out.add(cleaned)
        }
        MTPROTO_URI_REGEX.findAll(text).forEach { m ->
            val cleaned = cleanUri(m.value)
            if (parse(cleaned) != null) out.add(cleaned)
        }
        TG_SOCKS_URI_REGEX.findAll(text).forEach { m ->
            val cleaned = cleanUri(m.value)
            if (parse(cleaned) != null) out.add(cleaned)
        }
        extractWireGuardIniBlocks(text).forEach { wgUri ->
            if (parse(wgUri) != null) out.add(wgUri)
        }
        extractOpenVpnConfigs(text).forEach { ovpnUri ->
            if (parse(ovpnUri) != null) out.add(ovpnUri)
        }
    }

    /**
     * Extracts inline OpenVPN (`.ovpn` / `.openvpn`) profile directives posted in text/files
     * (`remote <host> <port>` paired with `client` / `dev ` / `proto ` / `<ca>`) into
     * normalized `openvpn://host:port?proto=...&dev=...#OpenVPN` URIs.
     */
    fun extractOpenVpnConfigs(rawText: String, label: String = "OpenVPN"): List<String> {
        if (!rawText.contains("remote ", ignoreCase = true)) return emptyList()
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val hasOvpnDirective = lines.any { line ->
            val t = line.trim().lowercase()
            t == "client" ||
                t.startsWith("dev ") ||
                t.startsWith("proto ") ||
                t.startsWith("<ca>") ||
                t.startsWith("<tls-auth>") ||
                t.startsWith("<tls-crypt>")
        }
        if (!hasOvpnDirective) return emptyList()

        var proto = "udp"
        var dev = "tun"
        var cipher = ""
        val out = LinkedHashSet<String>()
        for (rawLine in lines) {
            val clean = rawLine.substringBefore('#').substringBefore(';').trim()
            if (clean.isEmpty()) continue
            val parts = clean.split(Regex("\\s+"))
            if (parts.isEmpty()) continue
            when (parts[0].lowercase()) {
                "proto" -> if (parts.size >= 2) proto = parts[1].lowercase()
                "dev" -> if (parts.size >= 2) dev = parts[1].lowercase()
                "cipher", "data-ciphers" -> if (parts.size >= 2) cipher = parts[1]
                "remote" -> if (parts.size >= 2) {
                    val host = parts[1].trim()
                    val port = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 1194
                    val lineProto = parts.getOrNull(3)?.lowercase() ?: proto
                    if (host.isNotEmpty() && !host.contains('/') && !host.any { it.isWhitespace() }) {
                        proto = lineProto
                        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
                        val qp = mutableListOf("proto=${urlEncode(proto)}", "dev=${urlEncode(dev)}")
                        if (cipher.isNotBlank()) qp.add("cipher=${urlEncode(cipher)}")
                        val frag = urlEncode(label.ifBlank { "OpenVPN-$host" })
                        out.add("openvpn://$formattedHost:$port?${qp.joinToString("&")}#$frag")
                    }
                }
            }
        }
        return out.toList()
    }

    /** Converts an `openvpn://` / `ovpn://` URI back into a minimal client `.ovpn` profile. */
    fun openVpnUriToOvpn(uri: String): String? {
        val parsed = parse(uri) ?: return null
        if (parsed.protocol !in setOf("openvpn", "ovpn")) return null
        val params = parseQueryParams(uri.substringAfter('?', "").substringBefore('#'))
        val proto = params["proto"]?.takeIf { it.isNotBlank() } ?: "udp"
        val dev = params["dev"]?.takeIf { it.isNotBlank() } ?: "tun"
        val cipher = params["cipher"]?.takeIf { it.isNotBlank() }
        return buildString {
            appendLine("client")
            appendLine("dev $dev")
            appendLine("proto $proto")
            appendLine("remote ${parsed.host} ${parsed.port}")
            appendLine("resolv-retry infinite")
            appendLine("nobind")
            appendLine("persist-key")
            appendLine("persist-tun")
            if (cipher != null) appendLine("data-ciphers $cipher")
            appendLine("remote-cert-tls server")
            append("verb 3")
        }
    }

    /**
     * Scans raw text for WireGuard `.conf` INI blocks (`[Interface] ... [Peer]`) and converts each
     * valid block into a canonical `wireguard://` share URI so it can be stored, benchmarked, and
     * exported back to `.conf` cleanly.
     */
    fun extractWireGuardIniBlocks(rawText: String): List<String> {
        if (!rawText.contains("[Interface]", ignoreCase = true) ||
            !rawText.contains("[Peer]", ignoreCase = true)
        ) {
            return emptyList()
        }
        val out = LinkedHashSet<String>()
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val sections = normalized.split(Regex("(?=\\[Interface\\])", RegexOption.IGNORE_CASE))
        for (block in sections) {
            if (!block.contains("[Interface]", ignoreCase = true) ||
                !block.contains("[Peer]", ignoreCase = true)
            ) continue
            val uri = wireGuardIniToUri(block)
            if (uri != null) out.add(uri)
        }
        return out.toList()
    }

    /** Converts a WireGuard `.conf` INI snippet (`[Interface]` + `[Peer]`) into `wireguard://...`. */
    fun wireGuardIniToUri(iniText: String, label: String = "WireGuard"): String? {
        var inInterface = false
        var inPeer = false
        var privateKey = ""
        var address = ""
        var dns = ""
        var mtu = ""
        var publicKey = ""
        var presharedKey = ""
        var endpoint = ""
        var reserved = ""

        for (rawLine in iniText.lines()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            when {
                line.equals("[Interface]", ignoreCase = true) -> {
                    inInterface = true
                    inPeer = false
                    continue
                }
                line.equals("[Peer]", ignoreCase = true) -> {
                    if (inPeer && endpoint.isNotBlank() && publicKey.isNotBlank()) break
                    inInterface = false
                    inPeer = true
                    continue
                }
                line.startsWith("[") -> {
                    inInterface = false
                    inPeer = false
                    continue
                }
            }
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim().lowercase()
            val value = line.substring(eqIdx + 1).trim()
            if (value.isEmpty()) continue

            if (inInterface) {
                when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> address = value
                    "dns" -> dns = value
                    "mtu" -> mtu = value
                    "reserved" -> reserved = value
                }
            } else if (inPeer) {
                when (key) {
                    "publickey" -> publicKey = value
                    "presharedkey" -> presharedKey = value
                    "endpoint" -> endpoint = value
                    "reserved" -> if (reserved.isBlank()) reserved = value
                }
            }
        }

        if (privateKey.isBlank() || publicKey.isBlank() || endpoint.isBlank()) return null
        val (host, port) = splitHostPort(endpoint) ?: return null
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

        val queryParts = mutableListOf<String>()
        queryParts.add("publickey=${urlEncode(publicKey)}")
        if (address.isNotBlank()) queryParts.add("address=${urlEncode(address)}")
        if (dns.isNotBlank()) queryParts.add("dns=${urlEncode(dns)}")
        if (mtu.isNotBlank()) queryParts.add("mtu=${urlEncode(mtu)}")
        if (presharedKey.isNotBlank()) queryParts.add("presharedkey=${urlEncode(presharedKey)}")
        if (reserved.isNotBlank()) queryParts.add("reserved=${urlEncode(reserved)}")

        val frag = urlEncode(label.ifBlank { "WireGuard-$host" })
        return "wireguard://${urlEncode(privateKey)}@$formattedHost:$port?${queryParts.joinToString("&")}#$frag"
    }

    /** Converts a `wireguard://` / `wg://` / `warp://` share URI back into standard `.conf` INI format. */
    fun wireGuardUriToIni(uri: String): String? {
        val trimmed = uri.trim()
        val proto = protocolOf(trimmed)
        if (proto !in setOf("wireguard", "wg", "warp", "amneziawg")) return null
        val rest = trimmed.substringAfter("://").substringBefore('#')
        val userAndEndpoint = rest.substringBefore('?')
        val queryStr = rest.substringAfter('?', "")
        val (userPart, hostPortPart) = if (userAndEndpoint.contains('@')) {
            userAndEndpoint.substringBefore('@') to userAndEndpoint.substringAfter('@')
        } else {
            "" to userAndEndpoint
        }
        val (host, port) = splitHostPort(hostPortPart) ?: return null
        val params = parseQueryParams(queryStr)
        val privateKey = decodePercent(userPart).ifBlank { params["privatekey"].orEmpty() }
        val publicKey = params["publickey"] ?: params["pk"] ?: ""
        val address = params["address"] ?: "172.16.0.2/32"
        val dns = params["dns"] ?: "1.1.1.1"
        val mtu = params["mtu"] ?: "1280"
        val psk = params["presharedkey"] ?: params["psk"] ?: ""
        val reserved = params["reserved"] ?: ""
        val endpointHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

        return buildString {
            appendLine("[Interface]")
            if (privateKey.isNotBlank()) appendLine("PrivateKey = $privateKey")
            appendLine("Address = $address")
            appendLine("DNS = $dns")
            appendLine("MTU = $mtu")
            if (reserved.isNotBlank()) appendLine("Reserved = $reserved")
            appendLine()
            appendLine("[Peer]")
            if (publicKey.isNotBlank()) appendLine("PublicKey = $publicKey")
            if (psk.isNotBlank()) appendLine("PresharedKey = $psk")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            append("Endpoint = $endpointHost:$port")
        }
    }

    fun protocolOf(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains("[Interface]", ignoreCase = true) &&
            trimmed.contains("[Peer]", ignoreCase = true)
        ) {
            return "wireguard"
        }
        if (trimmed.contains("remote ", ignoreCase = true) &&
            (trimmed.contains("\nclient", ignoreCase = true) ||
                trimmed.startsWith("client", ignoreCase = true) ||
                trimmed.contains("dev tun", ignoreCase = true) ||
                trimmed.contains("<ca>", ignoreCase = true))
        ) {
            return "openvpn"
        }
        if (trimmed.startsWith("tg://proxy?", ignoreCase = true) ||
            Regex("^https?://(?:t\\.me|telegram\\.me)/proxy\\?", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        ) {
            return "mtproto"
        }
        if (trimmed.startsWith("tg://socks?", ignoreCase = true) ||
            Regex("^https?://(?:t\\.me|telegram\\.me)/socks\\?", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        ) {
            return "socks5"
        }
        val idx = trimmed.indexOf("://")
        return if (idx > 0) trimmed.substring(0, idx).lowercase() else "unknown"
    }

    fun familyOf(protocol: String): Family = when (protocol.lowercase()) {
        "wireguard", "wg", "warp", "amneziawg" -> Family.WIREGUARD
        "openvpn", "ovpn" -> Family.OPENVPN
        "npv", "npv4", "npvs", "npvt", "napsternetv" -> Family.NAPSTERNETV
        "mtproto" -> Family.TELEGRAM_MTPROTO
        "socks", "socks4", "socks5", "ssh", "sn", "dnstt", "slipnet" -> Family.PROXY_TUNNEL
        else -> Family.XRAY_SINGBOX
    }

    fun isWireGuardUri(uri: String): Boolean {
        val proto = protocolOf(uri)
        return proto in setOf("wireguard", "wg", "warp", "amneziawg")
    }

    fun isOpenVpnUri(uri: String): Boolean {
        val proto = protocolOf(uri)
        return proto in setOf("openvpn", "ovpn")
    }

    fun isMtProtoUri(uri: String): Boolean = protocolOf(uri) == "mtproto"

    /** Parses ANY supported share-link, WireGuard INI, or OpenVPN snippet into a structured endpoint. */
    fun parse(raw: String?): ParsedEndpoint? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        // Raw WireGuard INI snippet pasted directly
        if (trimmed.contains("[Interface]", ignoreCase = true) &&
            trimmed.contains("[Peer]", ignoreCase = true)
        ) {
            val converted = wireGuardIniToUri(trimmed) ?: return null
            return parse(converted)
        }

        // Raw OpenVPN .ovpn text pasted directly
        if (protocolOf(trimmed) == "openvpn" && !trimmed.contains("://")) {
            val converted = extractOpenVpnConfigs(trimmed).firstOrNull() ?: return null
            return parse(converted)
        }

        val proto = protocolOf(trimmed)
        if (proto !in SUPPORTED_PROTOCOLS) return null

        // VLESS
        if (proto == "vless") {
            val vless = TunnelConfig.parseVlessUri(trimmed) ?: return null
            return ParsedEndpoint(trimmed, "vless", Family.XRAY_SINGBOX, vless.host, vless.port, vless.label)
        }

        // Telegram MTProto proxy links
        if (proto == "mtproto") {
            return parseMtProto(trimmed)
        }

        // Telegram SOCKS5 share links (tg://socks?server=...&port=...&user=...&pass=...)
        if (proto == "socks5" && (trimmed.startsWith("tg://socks?", ignoreCase = true) ||
                Regex("^https?://(?:t\\.me|telegram\\.me)/socks\\?", RegexOption.IGNORE_CASE).containsMatchIn(trimmed))
        ) {
            val hop = TunnelConfig.parseProxyHop(trimmed) ?: return null
            return ParsedEndpoint(trimmed, "socks5", Family.PROXY_TUNNEL, hop.host, hop.port, "Telegram SOCKS5")
        }

        // VMess Base64(JSON)
        if (proto == "vmess") {
            val payload = trimmed.substringAfter("://").substringBefore('#').substringBefore('?')
            val json = tryDecodeBase64(payload) ?: return null
            val host = extractJsonString(json, "add") ?: extractJsonString(json, "host") ?: return null
            val port = extractJsonPort(json, "port") ?: return null
            val label = extractJsonString(json, "ps").orEmpty()
            return ParsedEndpoint(trimmed, "vmess", Family.XRAY_SINGBOX, host, port, label)
        }

        // SSR Base64(host:port:proto:method:obfs:pass/...)
        if (proto == "ssr") {
            val payload = trimmed.substringAfter("://").substringBefore('#')
            val decoded = tryDecodeBase64(payload) ?: return null
            val head = decoded.substringBefore('/')
            val (host, port) = splitHostPort(head.split(':').take(2).joinToString(":"))
                ?: head.split(':').takeIf { it.size >= 2 }?.let { parts ->
                    val p = parts[1].toIntOrNull()
                    if (parts[0].isNotBlank() && p != null && p in 1..65535) parts[0] to p else null
                } ?: return null
            val label = trimmed.substringAfter('#', "").let { decodePercent(it) }
            return ParsedEndpoint(trimmed, "ssr", Family.XRAY_SINGBOX, host, port, label)
        }

        // Shadowsocks (plain, SIP002, or whole-URI Base64)
        if (proto == "ss") {
            val noFrag = trimmed.substringAfter("://").substringBefore('#')
            val label = trimmed.substringAfter('#', "").let { decodePercent(it) }
            val hostPortPair = if (noFrag.contains('@')) {
                splitHostPort(noFrag.substringAfter('@').substringBefore('?').substringBefore('/'))
            } else {
                val b64 = noFrag.substringBefore('?')
                val decoded = tryDecodeBase64(b64) ?: return null
                if (!decoded.contains('@')) return null
                splitHostPort(decoded.substringAfter('@').substringBefore('?').substringBefore('/'))
            } ?: return null
            return ParsedEndpoint(trimmed, "ss", Family.XRAY_SINGBOX, hostPortPair.first, hostPortPair.second, label)
        }

        // NapsternetV (`npv://`, `npv4://`, `npvs://`, `npvt://`, `napsternetv://`)
        if (proto in setOf("npv", "npv4", "npvs", "npvt", "napsternetv")) {
            val label = trimmed.substringAfter('#', "").let { decodePercent(it) }
            val body = trimmed.substringAfter("://").substringBefore('#')
            val authoritySegment = body.substringBefore('?').substringBefore('/')
            if (authoritySegment.contains('@') || authoritySegment.contains(':')) {
                val hostPortPart = if (authoritySegment.contains('@')) {
                    authoritySegment.substringAfterLast('@')
                } else {
                    authoritySegment
                }
                val direct = splitHostPort(hostPortPart)
                if (direct != null) {
                    return ParsedEndpoint(trimmed, proto, Family.NAPSTERNETV, direct.first, direct.second, label)
                }
            }
            val decoded = tryDecodeBase64(body.substringBefore('?'))
            if (decoded != null) {
                // Nested link inside npv4/npv/npvs base64
                if (decoded.contains("://")) {
                    val inner = parse(decoded)
                    if (inner != null) {
                        return ParsedEndpoint(trimmed, proto, Family.NAPSTERNETV, inner.host, inner.port, inner.label.ifBlank { label })
                    }
                }
                val host = extractJsonString(decoded, "add")
                    ?: extractJsonString(decoded, "host")
                    ?: extractJsonString(decoded, "server")
                    ?: extractJsonString(decoded, "address")
                val port = extractJsonPort(decoded, "port")
                    ?: extractJsonPort(decoded, "serverPort")
                    ?: 443
                if (!host.isNullOrBlank()) {
                    val ps = extractJsonString(decoded, "ps")
                        ?: extractJsonString(decoded, "remarks")
                        ?: extractJsonString(decoded, "remark")
                        ?: extractJsonString(decoded, "name")
                        ?: extractJsonString(decoded, "tag")
                        ?: label
                    return ParsedEndpoint(trimmed, proto, Family.NAPSTERNETV, host, port, ps)
                }
            }
            val authorityHostPort = parseAuthorityHostPort(trimmed)
            if (authorityHostPort != null) {
                return ParsedEndpoint(trimmed, proto, Family.NAPSTERNETV, authorityHostPort.first, authorityHostPort.second, label)
            }
            return null
        }

        // Standard authority URIs (trojan, hy2, hysteria2, hysteria, tuic, juicity, anytls, naive+*, wireguard, wg, warp, openvpn, ovpn, socks*, ssh, sn, dnstt, slipnet)
        val hp = parseAuthorityHostPort(trimmed) ?: return null
        val label = trimmed.substringAfter('#', "").let { decodePercent(it) }
        return ParsedEndpoint(trimmed, proto, familyOf(proto), hp.first, hp.second, label)
    }

    fun extractHostAndPort(raw: String): Pair<String, Int>? {
        return parse(raw)?.let { it.host to it.port }
    }

    fun isPlausible(uri: String): Boolean {
        if (uri.length < 14 || uri.length > 4096) return false
        return parse(uri) != null
    }

    fun priorityRank(uri: String): Int = when (protocolOf(uri)) {
        "vless" -> 0
        "trojan" -> 1
        "hy2", "hysteria2", "hysteria" -> 2
        "tuic" -> 3
        "wireguard", "wg", "warp", "amneziawg" -> 4
        "openvpn", "ovpn" -> 5
        "vmess" -> 6
        "ss", "ssr" -> 7
        "npv4", "npvs", "npvt", "npv", "napsternetv" -> 8
        "juicity", "anytls", "naive+https", "naive+quic", "naive" -> 9
        "socks5", "socks", "socks4", "ssh", "sn", "dnstt", "slipnet" -> 10
        "mtproto" -> 11
        else -> 15
    }

    /**
     * Picks up to [limit] configs while preserving protocol diversity across all harvested sources
     * (prevents e.g. 120 VLESS links from crowding out harvested WireGuard, NapsternetV, or MTProto configs).
     */
    fun mergeBalanced(configs: Collection<String>, limit: Int = 250): List<String> {
        if (configs.isEmpty()) return emptyList()
        val grouped = configs.distinct().groupBy { protocolOf(it) }
        val result = LinkedHashSet<String>()

        // Round 1: guarantee representation from every harvested protocol in priority order
        val orderedProtocols = grouped.keys.sortedBy { proto ->
            grouped[proto]?.firstOrNull()?.let { priorityRank(it) } ?: 99
        }
        val queues = orderedProtocols.associateWith { ArrayDeque(grouped[it].orEmpty()) }
        var added = true
        while (result.size < limit && added) {
            added = false
            for (proto in orderedProtocols) {
                val q = queues[proto] ?: continue
                if (q.isNotEmpty()) {
                    result.add(q.removeFirst())
                    added = true
                    if (result.size >= limit) break
                }
            }
        }
        return result.sortedWith(compareBy<String> { priorityRank(it) }.thenBy { it.length })
    }

    /** Extracts chainable SOCKS5 / HTTP proxy hops from harvested URIs for multi-layer stacking. */
    fun extractChainableProxyHops(configs: Collection<String>): List<TunnelConfig.ProxyHop> {
        val out = LinkedHashMap<String, TunnelConfig.ProxyHop>()
        for (raw in configs) {
            val proto = protocolOf(raw)
            if (proto in setOf("socks5", "socks", "http")) {
                val hop = TunnelConfig.parseProxyHop(raw) ?: continue
                if (hop.scheme != TunnelConfig.ProxyScheme.DIRECT) {
                    out.putIfAbsent("${hop.scheme.name}:${hop.host}:${hop.port}", hop)
                }
            }
        }
        return out.values.toList()
    }

    private fun parseAuthorityHostPort(uriStr: String): Pair<String, Int>? {
        val afterScheme = uriStr.substringAfter("://")
        val authorityAndPath = afterScheme.substringBefore('#').substringBefore('?').substringBefore('/')
        val hostPortStr = if (authorityAndPath.contains('@')) authorityAndPath.substringAfterLast('@') else authorityAndPath
        val direct = splitHostPort(hostPortStr)
        if (direct != null) return direct

        // Fallback via java.net.URI for valid RFC URIs with default port 443
        return try {
            val u = URI(uriStr)
            val h = u.host?.trim().orEmpty()
            if (h.isEmpty()) return null
            val p = if (u.port in 1..65535) u.port else defaultPortFor(protocolOf(uriStr))
            h to p
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultPortFor(proto: String): Int = when (proto) {
        "ssh" -> 22
        "socks", "socks4", "socks5" -> 1080
        "wireguard", "wg", "warp", "amneziawg" -> 51820
        "openvpn", "ovpn" -> 1194
        else -> 443
    }

    private fun splitHostPort(raw: String): Pair<String, Int>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close <= 1) return null
            val host = s.substring(1, close).trim()
            val after = s.substring(close + 1)
            val port = if (after.startsWith(":")) after.substring(1).toIntOrNull() else null
            if (host.isEmpty() || port == null || port !in 1..65535) return null
            return host to port
        }
        val colon = s.lastIndexOf(':')
        if (colon <= 0) return null
        val host = s.substring(0, colon).trim()
        val port = s.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535 || host.any { it.isWhitespace() }) return null
        return host to port
    }

    private fun parseMtProto(trimmed: String): ParsedEndpoint? {
        val rawQuery = trimmed.substringAfter('?', "")
        val query = rawQuery.substringBefore('#')
        if (query.isBlank()) return null

        var server: String? = null
        var port: Int? = null
        var secret: String? = null

        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx <= 0) return null
            val key = pair.substring(0, idx).trim().lowercase()
            val decoded = decodeQueryComponent(pair.substring(idx + 1).trim()) ?: return null
            when (key) {
                "server", "host" -> {
                    val value = decoded.trim()
                    if (server != null ||
                        value.isEmpty() ||
                        decoded.any { it.isWhitespace() } ||
                        value.contains('/') ||
                        value.contains('#') ||
                        value.contains('?')
                    ) {
                        return null
                    }
                    server = value
                }
                "port" -> {
                    if (port != null || decoded.isEmpty() || !decoded.all { it in '0'..'9' }) {
                        return null
                    }
                    val p = decoded.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                    port = p
                }
                "secret" -> {
                    val value = decoded.trim()
                    if (secret != null || value.isEmpty() || decoded.any { it.isWhitespace() }) {
                        return null
                    }
                    secret = value
                }
                else -> return null
            }
        }

        val host = server ?: return null
        val validPort = port ?: return null
        if (secret == null) return null
        val label = rawQuery.substringAfter('#', "")
            .let { decodePercent(it).trim() }
            .ifBlank { "Telegram MTProto" }
        return ParsedEndpoint(trimmed, "mtproto", Family.TELEGRAM_MTPROTO, host, validPort, label)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                map[pair.substring(0, idx).lowercase()] = decodePercent(pair.substring(idx + 1))
            }
        }
        return map
    }

    private fun extractJsonString(json: String, field: String): String? {
        val m = Regex(""""$field"\s*:\s*"([^"]*)"""").find(json) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun extractJsonPort(json: String, field: String): Int? {
        val m = Regex(""""$field"\s*:\s*"?(\d{1,5})"?""").find(json) ?: return null
        val port = m.groupValues[1].toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace("<br>", "\n")
    }

    private fun cleanUri(raw: String): String {
        return raw.trim().trimEnd(',', ';', '.', ')', ']', '}')
    }

    private fun tryDecodeBase64(input: String): String? {
        val compact = input.trim().replace("\r", "").replace("\n", "")
        if (compact.length < 12 || compact.contains(' ')) return null
        return try {
            val bytes = decodeBase64Bytes(compact) ?: return null
            val text = String(bytes, Charsets.UTF_8)
            if (text.any { it.code < 9 && it != '\n' && it != '\r' && it != '\t' }) null else text
        } catch (_: Exception) {
            null
        }
    }

    /** Pure-Kotlin RFC-4648 standard + URL-safe Base64 decoder (safe on API 24+ and plain JVM tests). */
    private fun decodeBase64Bytes(input: String): ByteArray? {
        val clean = input.trimEnd('=')
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (ch in clean) {
            val v = when (ch) {
                in 'A'..'Z' -> ch.code - 'A'.code
                in 'a'..'z' -> ch.code - 'a'.code + 26
                in '0'..'9' -> ch.code - '0'.code + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> return null
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((acc ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private fun decodeQueryComponent(value: String): String? =
        try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun decodePercent(value: String): String {
        return decodeQueryComponent(value) ?: value
    }

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            value
        }
    }
}
