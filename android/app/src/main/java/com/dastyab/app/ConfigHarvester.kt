package com.dastyab.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * ConfigHarvester — 5-stage resilient proxy/VPN config harvester designed for
 * heavily censored networks and blackout conditions:
 *
 *  Stage 1: GitHub Direct Raw + jsDelivr CDN mirrors (prioritized whenever GitHub is reachable)
 *  Stage 2: Telegram Public Channels (`t.me/s/<channel>`)
 *  Stage 3: Detect active/installed alternate VPNs (WhiteDNS, WhiteVPN, SlipNet, Lantern, Tor/Orbot, etc.)
 *  Stage 4: Guide user to configure a temporary base bootstrap config, connect, and re-harvest
 *  Stage 5: Emergency DNS Blackout VPN (DoH / WhiteDNS) + Clean IP Scout fallback
 */
object ConfigHarvester {

    data class FeedSource(
        val name: String,
        val primaryUrl: String,
        val mirrorUrl: String? = null,
        val kind: SourceKind
    )

    enum class SourceKind { GITHUB, TELEGRAM }

    enum class HarvestOutcome {
        SUCCESS_GITHUB,
        SUCCESS_TELEGRAM,
        SUCCESS_MIXED,
        NEEDS_ALTERNATE_VPN,
        NEEDS_BOOTSTRAP_CONFIG,
        BLACKOUT_DNS_ONLY
    }

    data class FallbackTool(
        val name: String,
        val category: String,
        val packageNames: List<String>,
        val githubUrl: String,
        val description: String,
        val installedPackage: String? = null
    ) {
        val isInstalled: Boolean get() = installedPackage != null
    }

    data class HarvestReport(
        val outcome: HarvestOutcome,
        val configs: List<String>,
        val githubCount: Int,
        val telegramCount: Int,
        val usedMirror: Boolean,
        val activeVpnDetected: Boolean,
        val installedFallbackTools: List<FallbackTool>,
        val stepsLog: List<String>,
        val guidance: String
    )

    /** Curated GitHub feeds with jsDelivr CDN mirrors when raw.githubusercontent.com is throttled. */
    val DEFAULT_GITHUB_FEEDS = listOf(
        FeedSource(
            name = "barry-far V2ray-Configs (VLESS)",
            primaryUrl = "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Splitted-By-Protocol/vless.txt",
            mirrorUrl = "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Configs@main/Splitted-By-Protocol/vless.txt",
            kind = SourceKind.GITHUB
        ),
        FeedSource(
            name = "Epodonios v2ray-configs (VLESS)",
            primaryUrl = "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt",
            mirrorUrl = "https://cdn.jsdelivr.net/gh/Epodonios/v2ray-configs@main/Splitted-By-Protocol/vless.txt",
            kind = SourceKind.GITHUB
        ),
        FeedSource(
            name = "MahsaNetConfigTopic (Sub)",
            primaryUrl = "https://raw.githubusercontent.com/MahsaNetConfigTopic/config/main/xray_final.txt",
            mirrorUrl = "https://cdn.jsdelivr.net/gh/MahsaNetConfigTopic/config@main/xray_final.txt",
            kind = SourceKind.GITHUB
        ),
        FeedSource(
            name = "yebekhe Configs (VLESS)",
            primaryUrl = "https://raw.githubusercontent.com/yebekhe/TVC/main/subscriptions/xray/normal/vless",
            mirrorUrl = "https://cdn.jsdelivr.net/gh/yebekhe/TVC@main/subscriptions/xray/normal/vless",
            kind = SourceKind.GITHUB
        )
    )

    /** Public Telegram channels parsed via `https://t.me/s/<channel>`. */
    val DEFAULT_TELEGRAM_CHANNELS = listOf(
        "v2rayng_org",
        "PrivateVPNs",
        "DirectVPN",
        "vless_vmess"
    )

    /** Curated circumvention & blackout rescue tools (including user-requested GitHub projects). */
    val KNOWN_FALLBACK_TOOLS = listOf(
        FallbackTool(
            name = "WhiteDNS Android",
            category = "DNS Blackout",
            packageNames = listOf("com.whitedns.app", "org.whitedns.android", "ir.whitedns.app"),
            githubUrl = "https://github.com/WhiteDNS/WhiteDNS-Android",
            description = "Clean DNS & blackout bypass client"
        ),
        FallbackTool(
            name = "WhiteVPN",
            category = "Blackout VPN",
            packageNames = listOf("com.whitevpn.app", "org.whitevpn.android"),
            githubUrl = "https://github.com/WhiteDNS/WhiteVPN",
            description = "Resilient VPN engineered for restricted networks"
        ),
        FallbackTool(
            name = "IP Range Scout",
            category = "Clean IP Scanner",
            packageNames = listOf("com.whitedns.iprangescout", "org.whitedns.ipscout"),
            githubUrl = "https://github.com/WhiteDNS/IP-Range-Scout-Android",
            description = "Scans clean CDN/DNS IPv4 ranges on Android"
        ),
        FallbackTool(
            name = "SlipNet",
            category = "DNS / Slipstream Tunnel",
            packageNames = listOf("com.anonvector.slipnet", "org.slipnet.app"),
            githubUrl = "https://github.com/anonvector/SlipNet",
            description = "DNS & stealth tunnel for extreme censorship"
        ),
        FallbackTool(
            name = "ShiroKhorshid Tools",
            category = "Anti-Censorship",
            packageNames = listOf("org.shirokhorshid.app"),
            githubUrl = "https://github.com/shirokhorshid",
            description = "Community circumvention configs & utilities"
        ),
        FallbackTool(
            name = "Lantern",
            category = "Fallback VPN",
            packageNames = listOf("org.getlantern.lantern"),
            githubUrl = "https://github.com/getlantern",
            description = "Multi-protocol censorship-resistant proxy"
        ),
        FallbackTool(
            name = "Tor / Orbot",
            category = "Onion Routing",
            packageNames = listOf("org.torproject.android", "org.torproject.torbrowser"),
            githubUrl = "https://github.com/thetorproject",
            description = "Tor network proxy & bridges for severe blocks"
        ),
        FallbackTool(
            name = "v2rayNG",
            category = "Xray Client",
            packageNames = listOf("com.v2ray.ang"),
            githubUrl = "https://github.com/2dust/v2rayNG",
            description = "Full VLESS / VMess / Trojan / Reality client"
        ),
        FallbackTool(
            name = "Hiddify",
            category = "Multi-Protocol VPN",
            packageNames = listOf("app.hiddify.com"),
            githubUrl = "https://github.com/hiddify/hiddify-app",
            description = "Sing-box multi-protocol client with auto-fragment"
        ),
        FallbackTool(
            name = "NekoBox",
            category = "Sing-Box Client",
            packageNames = listOf("moe.nb4a"),
            githubUrl = "https://github.com/MatsuriDayo/NekoBoxForAndroid",
            description = "Universal proxy toolchain for Android"
        ),
        FallbackTool(
            name = "Psiphon",
            category = "Fallback VPN",
            packageNames = listOf("com.psiphon3.subscription", "com.psiphon3"),
            githubUrl = "https://github.com/Psiphon-Labs",
            description = "SSH/Obfuscated fallback circumvention tool"
        ),
        FallbackTool(
            name = "Cloudflare WARP",
            category = "Edge Tunnel",
            packageNames = listOf("com.cloudflare.onedotonedotonedotone"),
            githubUrl = "https://1.1.1.1/",
            description = "1.1.1.1 DNS + WireGuard edge tunnel"
        )
    )

    private val PROXY_URI_REGEX = Regex(
        """(vless|vmess|trojan|ss|hy2|hysteria2|tuic)://[^\s"'<>`\\]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Pure-Kotlin extractor: decodes HTML entities, optionally decodes Base64 subscription blobs,
     * and returns deduplicated proxy URIs with `vless://` prioritized first.
     */
    fun extractProxyConfigs(rawPayload: String, maxConfigs: Int = 120): List<String> {
        if (rawPayload.isBlank()) return emptyList()
        val unescaped = unescapeHtml(rawPayload)
        val found = LinkedHashSet<String>()

        // 1. Direct scan on raw/HTML-unescaped text
        PROXY_URI_REGEX.findAll(unescaped).forEach { match ->
            val cleaned = cleanUri(match.value)
            if (isPlausibleProxyUri(cleaned)) found.add(cleaned)
        }

        // 2. If nothing (or very few) found, try decoding as a Base64 subscription
        if (found.isEmpty()) {
            val decoded = tryDecodeBase64(unescaped)
            if (decoded != null) {
                PROXY_URI_REGEX.findAll(decoded).forEach { match ->
                    val cleaned = cleanUri(match.value)
                    if (isPlausibleProxyUri(cleaned)) found.add(cleaned)
                }
            }
        }

        // Prioritize VLESS first (directly supported by DastYab's VLESS parser), then Trojan/VMess/others
        return found
            .sortedWith(compareBy<String> { priorityRank(it) }.thenBy { it.length })
            .take(maxConfigs)
    }

    fun protocolOf(uri: String): String {
        val idx = uri.indexOf("://")
        return if (idx > 0) uri.substring(0, idx).lowercase() else "unknown"
    }

    /** Builds a GitHub raw URL -> jsDelivr mirror URL if possible. */
    fun toJsDelivrMirror(rawGithubUrl: String): String? {
        val prefix = "https://raw.githubusercontent.com/"
        if (!rawGithubUrl.startsWith(prefix, ignoreCase = true)) return null
        val rest = rawGithubUrl.substring(prefix.length)
        val parts = rest.split('/', limit = 4)
        if (parts.size < 4) return null
        val (owner, repo, branch, path) = parts
        return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
    }

    /** Detects which fallback circumvention apps are installed on this Android device. */
    fun detectFallbackTools(context: Context): List<FallbackTool> {
        val pm = context.packageManager
        return KNOWN_FALLBACK_TOOLS.map { tool ->
            val installed = tool.packageNames.firstOrNull { pkg -> isPackageInstalled(pm, pkg) }
            tool.copy(installedPackage = installed)
        }
    }

    /** Checks if ANY VPN (DastYab or another app like Lantern/Orbot/WhiteVPN/v2rayNG) is currently active. */
    fun isAnyVpnActive(context: Context): Boolean {
        if (DastyabVpnService.isConnected()) return true
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(active) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Runs the full resilient multi-stage harvest:
     *  1. Try GitHub raw feeds first (+ jsDelivr mirror fallback if raw GitHub is blocked).
     *  2. Try Telegram public channels (`t.me/s/<channel>`).
     *  3. If both fail, inspect active VPNs & installed fallback tools, and return actionable
     *     guidance (Alternate VPN → Bootstrap Temporary Config → Emergency DNS Blackout VPN).
     */
    fun harvestResilient(
        context: Context,
        customGithubUrls: List<String> = emptyList(),
        customTelegramChannels: List<String> = emptyList(),
        onProgress: (String) -> Unit = {}
    ): HarvestReport {
        val log = mutableListOf<String>()
        fun logStep(msg: String) {
            log.add(msg)
            onProgress(msg)
        }

        val activeVpn = isAnyVpnActive(context)
        val fallbackTools = detectFallbackTools(context)
        val installedTools = fallbackTools.filter { it.isInstalled }
        logStep("Network check: activeVpn=$activeVpn, fallbackAppsInstalled=${installedTools.size}")

        val allConfigs = LinkedHashSet<String>()
        var githubCount = 0
        var telegramCount = 0
        var usedMirror = false

        // ── STAGE 1: GitHub Direct + jsDelivr Mirror Fallback ──
        logStep("Stage 1/4: Trying GitHub config feeds first…")
        val githubFeeds = buildList {
            customGithubUrls.filter { it.isNotBlank() }.forEachIndexed { idx, url ->
                add(FeedSource("Custom GitHub #${idx + 1}", url.trim(), toJsDelivrMirror(url.trim()), SourceKind.GITHUB))
            }
            addAll(DEFAULT_GITHUB_FEEDS)
        }

        for (feed in githubFeeds) {
            val body = httpGet(feed.primaryUrl, timeoutMs = 5500)
            if (body != null) {
                val extracted = extractProxyConfigs(body, maxConfigs = 40)
                if (extracted.isNotEmpty()) {
                    allConfigs.addAll(extracted)
                    githubCount += extracted.size
                    logStep("  ✓ GitHub direct [${feed.name}]: +${extracted.size} configs")
                    if (githubCount >= 60) break
                    continue
                }
            }
            // Fallback to jsDelivr mirror if GitHub raw was blocked/timed out
            val mirror = feed.mirrorUrl
            if (mirror != null) {
                val mirrorBody = httpGet(mirror, timeoutMs = 5500)
                if (mirrorBody != null) {
                    val extracted = extractProxyConfigs(mirrorBody, maxConfigs = 40)
                    if (extracted.isNotEmpty()) {
                        usedMirror = true
                        allConfigs.addAll(extracted)
                        githubCount += extracted.size
                        logStep("  ✓ GitHub mirror (jsDelivr) [${feed.name}]: +${extracted.size} configs")
                        if (githubCount >= 60) break
                        continue
                    }
                }
            }
            logStep("  ✗ Unreachable: ${feed.name}")
        }

        // ── STAGE 2: Telegram Public Channels ──
        logStep("Stage 2/4: Trying Telegram public channels (t.me/s/…)…")
        val channels = (customTelegramChannels + DEFAULT_TELEGRAM_CHANNELS)
            .map { it.trim().removePrefix("@").removePrefix("https://t.me/s/").removePrefix("https://t.me/") }
            .filter { it.isNotEmpty() }
            .distinct()

        for (channel in channels) {
            val url = "https://t.me/s/$channel"
            val body = httpGet(url, timeoutMs = 5000)
            if (body != null) {
                val extracted = extractProxyConfigs(body, maxConfigs = 30)
                if (extracted.isNotEmpty()) {
                    allConfigs.addAll(extracted)
                    telegramCount += extracted.size
                    logStep("  ✓ Telegram @$channel: +${extracted.size} configs")
                    if (telegramCount >= 40) break
                    continue
                }
            }
            logStep("  ✗ Telegram @$channel unreachable")
        }

        val finalList = allConfigs
            .sortedWith(compareBy<String> { priorityRank(it) }.thenBy { it.length })
            .take(120)

        if (finalList.isNotEmpty()) {
            // Persist harvested configs so user has an offline pool during future blackouts
            TunnelConfig.saveHarvestedConfigs(context, finalList)
            val outcome = when {
                githubCount > 0 && telegramCount > 0 -> HarvestOutcome.SUCCESS_MIXED
                githubCount > 0 -> HarvestOutcome.SUCCESS_GITHUB
                else -> HarvestOutcome.SUCCESS_TELEGRAM
            }
            val msg = "Harvested ${finalList.size} configs (GitHub: $githubCount, Telegram: $telegramCount). Saved for offline use."
            logStep("OK: $msg")
            return HarvestReport(
                outcome = outcome,
                configs = finalList,
                githubCount = githubCount,
                telegramCount = telegramCount,
                usedMirror = usedMirror,
                activeVpnDetected = activeVpn,
                installedFallbackTools = installedTools,
                stepsLog = log,
                guidance = msg
            )
        }

        // ── STAGE 3 & 4: Both GitHub & Telegram unreachable ──
        logStep("Stage 3/4: GitHub & Telegram unreachable. Checking alternate VPNs & bootstrap options…")
        val savedBootstrap = TunnelConfig.load(context).bootstrapConfig
        if (savedBootstrap.isNotBlank() && !activeVpn) {
            logStep("  ℹ Temporary bootstrap config is saved — connect with it first, then re-harvest.")
        }

        return when {
            installedTools.isNotEmpty() && !activeVpn -> {
                val names = installedTools.joinToString(", ") { it.name }
                val guide = "GitHub and Telegram are blocked on this connection. " +
                    "Detected fallback VPN(s): $names. " +
                    "Tap any installed fallback VPN below to connect temporarily, then tap Harvest Again — " +
                    "OR paste a temporary base working config into DastYab, OR activate Emergency DNS Blackout VPN."
                logStep("Stage 4/4: Fallback VPNs available ($names).")
                HarvestReport(
                    outcome = HarvestOutcome.NEEDS_ALTERNATE_VPN,
                    configs = TunnelConfig.loadHarvestedConfigs(context),
                    githubCount = 0,
                    telegramCount = 0,
                    usedMirror = false,
                    activeVpnDetected = false,
                    installedFallbackTools = installedTools,
                    stepsLog = log,
                    guidance = guide
                )
            }
            !activeVpn -> {
                val guide = "No direct access to GitHub or Telegram and no fallback VPN is active. " +
                    "Two recovery options:\n" +
                    "1) Tap 'Set Temporary Base Config' to paste any single working vless:// / vmess:// / trojan:// link, connect DastYab VPN, and then re-harvest free configs automatically.\n" +
                    "2) Under total blackout, activate 'Emergency DNS Blackout VPN' (WhiteDNS / DoH / SlipNet) and run 'Clean IP & DNS Scout'."
                logStep("Stage 4/4: Requesting Temporary Base Config or Emergency DNS Blackout VPN.")
                HarvestReport(
                    outcome = HarvestOutcome.NEEDS_BOOTSTRAP_CONFIG,
                    configs = TunnelConfig.loadHarvestedConfigs(context),
                    githubCount = 0,
                    telegramCount = 0,
                    usedMirror = false,
                    activeVpnDetected = false,
                    installedFallbackTools = installedTools,
                    stepsLog = log,
                    guidance = guide
                )
            }
            else -> {
                val guide = "Even with an active VPN, GitHub & Telegram timed out (severe blackout / DNS poisoning). " +
                    "Switch to 'Emergency DNS Blackout VPN' (DoH / WhiteDNS), run 'Clean IP & DNS Scout' to find a clean edge IP, " +
                    "or replace your temporary base config with a fresh working link."
                logStep("Stage 4/4: Severe blackout detected — recommending DNS Blackout VPN + IP Scout.")
                HarvestReport(
                    outcome = HarvestOutcome.BLACKOUT_DNS_ONLY,
                    configs = TunnelConfig.loadHarvestedConfigs(context),
                    githubCount = 0,
                    telegramCount = 0,
                    usedMirror = false,
                    activeVpnDetected = true,
                    installedFallbackTools = installedTools,
                    stepsLog = log,
                    guidance = guide
                )
            }
        }
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) DastYab/1.7")
                setRequestProperty("Accept", "text/plain, text/html, */*")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var total = 0
                while (total < 350_000) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
                sb.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("<br/>", "\n")
            .replace("<br>", "\n")
    }

    private fun tryDecodeBase64(input: String): String? {
        val compact = input.trim().replace("\r", "").replace("\n", "")
        if (compact.length < 16 || compact.contains(' ')) return null
        return try {
            val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Base64.getDecoder().decode(compact)
                } catch (_: Exception) {
                    Base64.getUrlDecoder().decode(compact)
                }
            } else {
                android.util.Base64.decode(compact, android.util.Base64.DEFAULT)
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanUri(raw: String): String {
        return raw.trim().trimEnd(',', ';', '.', ')', ']', '}')
    }

    private fun isPlausibleProxyUri(uri: String): Boolean {
        if (uri.length < 18 || uri.length > 2048) return false
        return if (uri.startsWith("vless://", ignoreCase = true)) {
            TunnelConfig.parseVlessUri(uri) != null
        } else {
            uri.contains("://") && (uri.contains("@") || uri.startsWith("vmess://", ignoreCase = true))
        }
    }

    private fun priorityRank(uri: String): Int = when (protocolOf(uri)) {
        "vless" -> 0
        "trojan" -> 1
        "hy2", "hysteria2" -> 2
        "tuic" -> 3
        "vmess" -> 4
        "ss" -> 5
        else -> 9
    }
}
