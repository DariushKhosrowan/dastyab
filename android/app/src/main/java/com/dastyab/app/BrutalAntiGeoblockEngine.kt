package com.dastyab.app

import android.content.Context
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger

/**
 * BrutalAntiGeoblockEngine ("Lion Engine") — Heavy Multi-Layer Anti-Geoblock & Stealth Engine
 * dedicated to Google AI Studio and general censored web access (completely decoupled from
 * Google Flow's standalone [FlowWarriorEngine]).
 *
 * Key circumvention capabilities:
 *  1. Authentic Chromium 131 header coherence (never leaks identifiable bot UAs or forged
 *     proxy-spoof headers like `Via: 1.1 Google` / `CF-Connecting-IP: 8.8.8.8` that trigger WAF blocks).
 *  2. 55+ ASN / Cloud / Datacenter / WARP / Tor reputation classification ([ExitReputationTier]).
 *  3. Country-aligned Timezone / Locale / WebRTC / Geolocation JS shield for WebView.
 *  4. Privacy-first No-ECS DoH resolver rotation (Cloudflare, Quad9, Mullvad, AdGuard).
 *  5. Isolated 4-round 64-candidate "Lion" Multi-Hop & Entry-Laundering Escalation Engine.
 */
object BrutalAntiGeoblockEngine {

    const val LION_TITLE = "Brutal Lion (Heavy Anti-Geoblock)"
    const val LION_BADGE_PREFIX = "🦁 Lion"
    private const val PREFS_NAME = "dastyab_lion_engine"
    private const val KEY_BLACKLIST_IPS = "lion_blacklist_ips_v1"
    private const val KEY_LAST_CLEAN_CHAIN = "lion_last_clean_chain_v1"
    private const val MAX_BLACKLIST_IPS = 64

    private val rotationCounter = AtomicInteger(0)
    private val dpiEscalationLevel = AtomicInteger(1)

    const val CHROME_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val CHROME_DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Zero-ECS / censorship-resistant DoH resolvers ordered by privacy & speed. */
    val NO_ECS_DOH_URLS: List<String> = listOf(
        "https://1.1.1.1/dns-query",
        "https://9.9.9.9/dns-query",
        "https://dns.quad9.net/dns-query",
        "https://cloudflare-dns.com/dns-query",
        "https://dns.mullvad.net/dns-query",
        "https://dns.adguard-dns.com/dns-query",
        "https://8.8.8.8/dns-query"
    )

    /**
     * ASN / ISP / organization reputation tiers for strict geoblock circumvention.
     * Google Flow and AI Studio aggressively rate-limit / block known cloud & WARP subnets.
     */
    enum class ExitReputationTier(val label: String, val isHighRisk: Boolean) {
        RESIDENTIAL_ISP("Residential/ISP", false),
        CLEAN_NON_DC("Clean Non-DC", false),
        DATACENTER_LOW_RISK("Datacenter (Low Risk)", true),
        HOSTING_HIGH_RISK("Hosting/VPN Datacenter", true),
        WARP_OR_TOR("WARP/Tor Flagged", true)
    }

    enum class DpiPaddingLevel(
        val sliceTargetCount: Int,
        val minJitterMs: Int,
        val maxJitterMs: Int,
        val dohBlockSize: Int
    ) {
        LIGHT(sliceTargetCount = 3, minJitterMs = 0, maxJitterMs = 1, dohBlockSize = 128),
        ADAPTIVE(sliceTargetCount = 5, minJitterMs = 1, maxJitterMs = 3, dohBlockSize = 128),
        AGGRESSIVE(sliceTargetCount = 7, minJitterMs = 2, maxJitterMs = 5, dohBlockSize = 192),
        STEALTH_BURST(sliceTargetCount = 9, minJitterMs = 2, maxJitterMs = 7, dohBlockSize = 256);

        // Preserve parameter name clarity via primary constructor below
        @Suppress("unused")
        val targetSegments: Int get() = sliceTargetCount

        companion object {
            fun fromEscalation(level: Int): DpiPaddingLevel = when {
                level <= 0 -> LIGHT
                level == 1 -> ADAPTIVE
                level == 2 -> AGGRESSIVE
                else -> STEALTH_BURST
            }
        }
    }

    /** Egress country timezone + locale alignment profile to prevent browser geo-mismatch leaks. */
    data class CountryGeoProfile(
        val countryCode: String,
        val timeZoneId: String,
        val utcOffsetMinutesWest: Int, // Matches JS Date.prototype.getTimezoneOffset() (e.g. NYC EST = +300)
        val localeTag: String,
        val acceptLanguage: String
    )

    private val COUNTRY_GEO_PROFILES: Map<String, CountryGeoProfile> = listOf(
        CountryGeoProfile("US", "America/New_York", 300, "en-US", "en-US,en;q=0.9"),
        CountryGeoProfile("CA", "America/Toronto", 300, "en-CA", "en-CA,en-US;q=0.9,en;q=0.8"),
        CountryGeoProfile("GB", "Europe/London", 0, "en-GB", "en-GB,en-US;q=0.9,en;q=0.8"),
        CountryGeoProfile("DE", "Europe/Berlin", -60, "de-DE", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("NL", "Europe/Amsterdam", -60, "nl-NL", "nl-NL,nl;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("CH", "Europe/Zurich", -60, "de-CH", "de-CH,de;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("FR", "Europe/Paris", -60, "fr-FR", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("IE", "Europe/Dublin", 0, "en-IE", "en-IE,en-GB;q=0.9,en;q=0.8"),
        CountryGeoProfile("SE", "Europe/Stockholm", -60, "sv-SE", "sv-SE,sv;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("NO", "Europe/Oslo", -60, "nb-NO", "nb-NO,no;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("DK", "Europe/Copenhagen", -60, "da-DK", "da-DK,da;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("FI", "Europe/Helsinki", -120, "fi-FI", "fi-FI,fi;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("AT", "Europe/Vienna", -60, "de-AT", "de-AT,de;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("BE", "Europe/Brussels", -60, "fr-BE", "fr-BE,nl-BE;q=0.9,en-US;q=0.8"),
        CountryGeoProfile("IT", "Europe/Rome", -60, "it-IT", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("ES", "Europe/Madrid", -60, "es-ES", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("PL", "Europe/Warsaw", -60, "pl-PL", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("RO", "Europe/Bucharest", -120, "ro-RO", "ro-RO,ro;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("JP", "Asia/Tokyo", -540, "ja-JP", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("SG", "Asia/Singapore", -480, "en-SG", "en-SG,en-US;q=0.9,en;q=0.8"),
        CountryGeoProfile("KR", "Asia/Seoul", -540, "ko-KR", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("AU", "Australia/Sydney", -600, "en-AU", "en-AU,en-US;q=0.9,en;q=0.8"),
        CountryGeoProfile("IN", "Asia/Kolkata", -330, "en-IN", "en-IN,en-US;q=0.9,en;q=0.8"),
        CountryGeoProfile("TR", "Europe/Istanbul", -180, "tr-TR", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("AE", "Asia/Dubai", -240, "en-AE", "en-AE,en-US;q=0.9,en;q=0.8"),
        CountryGeoProfile("BR", "America/Sao_Paulo", 180, "pt-BR", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"),
        CountryGeoProfile("MX", "America/Mexico_City", 360, "es-MX", "es-MX,es;q=0.9,en-US;q=0.8,en;q=0.7")
    ).associateBy { it.countryCode }

    val HOSTING_AND_VPN_ASN_KEYWORDS: List<String> = listOf(
        "datacenter", "data center", "hosting", "cloud", "vps", "server", "colocation",
        "amazon", "aws", "ec2", "google cloud", "google llc", "gcp", "microsoft", "azure",
        "oracle", "cloudflare", "warp", "digitalocean", "hetzner", "ovh", "linode", "akamai",
        "vultr", "choopa", "alibaba", "aliyun", "tencent", "contabo", "scaleway", "online s.a.s",
        "kamatera", "upcloud", "ionos", "1&1", "hostinger", "godaddy", "m247", "leaseweb",
        "cdn77", "datacamp", "g-core", "gcore", "packethub", "buyvm", "frantech", "colocrossing",
        "racknerd", "fly.io", "vercel", "render", "railway", "cogent", "hurricane electric",
        "zscaler", "fastly", "mullvad", "nordvpn", "proton", "surfshark", "windscribe",
        "private internet access", "expressvpn", "cyberghost", "psiphon", "lantern", "tor-exit",
        "tor exit", "anchorfree", "opera software"
    )

    val RESIDENTIAL_ISP_HINT_KEYWORDS: List<String> = listOf(
        "telecom", "telekom", "communications", "comcast", "xfinity", "verizon", "at&t",
        "charter", "spectrum", "cox", "t-mobile", "vodafone", "orange", "telefonica",
        "rogers", "bell canada", "telus", "bt", "virgin media", "sky uk", "deutsche telekom",
        "swisscom", "kpn", "ziggo", "ntt", "kddi", "softbank", "singtel", "telstra",
        "fiber", "broadband", "cable", "wireless", "mobile", "isp", "residential"
    )

    /** Looks up coherent browser timezone & locale settings for an egress country ISO code. */
    fun geoProfileForCountry(countryCode: String?): CountryGeoProfile {
        val norm = countryCode?.trim()?.uppercase().orEmpty()
        return COUNTRY_GEO_PROFILES[norm] ?: COUNTRY_GEO_PROFILES.getValue("US")
    }

    /** Returns true if [orgOrIsp] matches known datacenter, cloud, VPS, or commercial VPN ASNs. */
    fun isHostingOrDatacenterOrg(orgOrIsp: String?): Boolean {
        val norm = orgOrIsp?.trim()?.lowercase().orEmpty()
        if (norm.isEmpty()) return false
        return HOSTING_AND_VPN_ASN_KEYWORDS.any { norm.contains(it) }
    }

    /**
     * Classifies an egress IP's reputation tier for strict anti-geoblock prioritization.
     * Cloudflare WARP & Tor exits receive [ExitReputationTier.WARP_OR_TOR] so Warrior/Lion
     * launder them through clean residential exits instead of trusting raw WARP directly.
     */
    fun classifyExitReputation(
        org: String? = null,
        isp: String? = null,
        isHosting: Boolean = false,
        isProxy: Boolean = false,
        isVpn: Boolean = false,
        isTor: Boolean = false,
        warpStatus: String = "off"
    ): ExitReputationTier {
        val combined = listOfNotNull(org, isp).joinToString(" ").trim().lowercase()
        val warpOn = warpStatus.equals("on", ignoreCase = true) ||
            warpStatus.equals("plus", ignoreCase = true) ||
            combined.contains("cloudflare warp")
        if (isTor || warpOn || combined.contains("tor-exit") || combined.contains("tor exit")) {
            return ExitReputationTier.WARP_OR_TOR
        }
        val orgDc = isHostingOrDatacenterOrg(combined)
        if (isHosting || orgDc || isVpn) {
            return ExitReputationTier.HOSTING_HIGH_RISK
        }
        if (isProxy) {
            return ExitReputationTier.DATACENTER_LOW_RISK
        }
        if (RESIDENTIAL_ISP_HINT_KEYWORDS.any { combined.contains(it) }) {
            return ExitReputationTier.RESIDENTIAL_ISP
        }
        return ExitReputationTier.CLEAN_NON_DC
    }

    /** Applies clean, coherent Chromium 131 headers without automation tokens or spoofed proxy headers. */
    fun applyCleanBrowserHeaders(
        conn: HttpURLConnection,
        countryCode: String? = "US",
        mobile: Boolean = true
    ) {
        val profile = geoProfileForCountry(countryCode)
        val ua = if (mobile) CHROME_MOBILE_UA else CHROME_DESKTOP_UA
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", profile.acceptLanguage)
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("Pragma", "no-cache")
        conn.setRequestProperty("Upgrade-Insecure-Requests", "1")
        conn.setRequestProperty("Sec-CH-UA", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        conn.setRequestProperty("Sec-CH-UA-Mobile", if (mobile) "?1" else "?0")
        conn.setRequestProperty("Sec-CH-UA-Platform", if (mobile) "\"Android\"" else "\"Windows\"")
        conn.setRequestProperty("Sec-Fetch-Dest", "document")
        conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
        conn.setRequestProperty("Sec-Fetch-Site", "none")
        conn.setRequestProperty("Sec-Fetch-User", "?1")
    }

    /** Backwards-compatible wrapper applying stealth browser headers cleanly. */
    fun applyBrutalHeaders(conn: HttpURLConnection, mobile: Boolean = false) {
        val count = rotationCounter.getAndIncrement()
        val cc = if (count % 3 == 0) "GB" else "US"
        applyCleanBrowserHeaders(conn, countryCode = cc, mobile = mobile)
    }

    /** Returns clean HTTP headers suitable for `WebView.loadUrl(url, headers)` without forged proxy headers. */
    fun getBrutalHeadersMap(countryCode: String? = "US"): Map<String, String> {
        val profile = geoProfileForCountry(countryCode)
        return mapOf(
            "Accept-Language" to profile.acceptLanguage,
            "Cache-Control" to "no-cache, no-store",
            "Pragma" to "no-cache",
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    /** Records a suspected DPI block/reset and escalates adaptive packet padding up to level 3. */
    fun recordDpiBlockAndEscalate(): DpiPaddingLevel {
        val lvl = dpiEscalationLevel.updateAndGet { cur -> (cur + 1).coerceAtMost(3) }
        return DpiPaddingLevel.fromEscalation(lvl)
    }

    /** Relaxes DPI escalation gradually once sub-300ms healthy traffic flows cleanly. */
    fun recordDpiCleanPass(): DpiPaddingLevel {
        val lvl = dpiEscalationLevel.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }
        return DpiPaddingLevel.fromEscalation(lvl)
    }

    fun currentDpiPaddingLevel(): DpiPaddingLevel = DpiPaddingLevel.fromEscalation(dpiEscalationLevel.get())

    /** Pure-Kotlin adaptive DPI level selector based on recent failures and observed RTT. */
    fun selectAdaptiveDpiLevel(consecutiveFailures: Int, lastLatencyMs: Long): DpiPaddingLevel = when {
        consecutiveFailures >= 3 -> DpiPaddingLevel.STEALTH_BURST
        consecutiveFailures >= 1 || lastLatencyMs > FailoverWatchdog.LATENCY_SWITCH_THRESHOLD_MS -> DpiPaddingLevel.AGGRESSIVE
        lastLatencyMs in 1..150L -> DpiPaddingLevel.LIGHT
        else -> DpiPaddingLevel.ADAPTIVE
    }

    /**
     * Computes adaptive non-uniform TCP segment slices `start..end` for TLS ClientHello packets
     * so DPI SNI extractors and fixed-length packet-size classifiers never see a predictable boundary.
     */
    fun computeAdaptiveClientHelloSlices(
        length: Int,
        level: DpiPaddingLevel = currentDpiPaddingLevel(),
        seed: Long = System.nanoTime()
    ): List<IntRange> {
        if (length <= 0) return emptyList()
        if (length <= 8) return listOf(0 until length)

        val rng = kotlin.random.Random(seed)
        val cuts = sortedSetOf<Int>()
        // Always split inside TLS record header (bytes 1..5) and handshake prefix (bytes 6..18)
        cuts.add(rng.nextInt(1, 4).coerceAtMost(length - 1))
        if (length > 10) cuts.add(rng.nextInt(5, 10).coerceAtMost(length - 1))
        if (length > 24) cuts.add(rng.nextInt(12, 24).coerceAtMost(length - 1))

        val desiredSlices = level.sliceTargetCount.coerceIn(3, 12)
        var attempts = 0
        while (cuts.size < desiredSlices - 1 && length > 32 && attempts < 24) {
            val c = rng.nextInt(24, length)
            cuts.add(c)
            attempts++
        }

        val out = ArrayList<IntRange>(cuts.size + 1)
        var prev = 0
        for (c in cuts) {
            if (c in (prev + 1) until length) {
                out.add(prev until c)
                prev = c
            }
        }
        if (prev < length) out.add(prev until length)
        return out
    }

    /** Picks an RFC 8467 DoH padding block size + small random jitter matching [level]. */
    fun computeAdaptiveDohPaddingTarget(
        level: DpiPaddingLevel = currentDpiPaddingLevel(),
        seed: Long = System.nanoTime()
    ): Pair<Int, Int> {
        val rng = kotlin.random.Random(seed)
        val jitter = when (level) {
            DpiPaddingLevel.LIGHT -> 0
            DpiPaddingLevel.ADAPTIVE -> rng.nextInt(0, 16)
            DpiPaddingLevel.AGGRESSIVE -> rng.nextInt(8, 32)
            DpiPaddingLevel.STEALTH_BURST -> rng.nextInt(16, 48)
        }
        return level.dohBlockSize to jitter
    }

    /**
     * Generates a JavaScript anti-leak shield injected into [InAppBrowserActivity] WebView:
     *  • Blocks WebRTC ICE/STUN/mDNS leaks (`RTCPeerConnection` / `webkitRTCPeerConnection` / `mozRTCPeerConnection`)
     *  • Aligns `Intl.DateTimeFormat().resolvedOptions().timeZone` & `Date.prototype.getTimezoneOffset` to [countryCode]
     *  • Aligns `navigator.language` & `navigator.languages` to the exit country
     *  • Neutralizes `navigator.geolocation` & `navigator.webdriver` fingerprinting
     */
    fun buildWebViewAntiLeakScript(countryCode: String?): String {
        val profile = geoProfileForCountry(countryCode)
        val langPrefix = profile.localeTag.substringBefore('-')
        val safeTz = profile.timeZoneId.replace("'", "")
        val safeLocale = profile.localeTag.replace("'", "")
        val safeLangPrefix = langPrefix.replace("'", "")
        val offset = profile.utcOffsetMinutesWest
        return """
            (function(){
              try {
                if (window.__dastyabAntiLeakActive === '$safeLocale|$safeTz') return;
                window.__dastyabAntiLeakActive = '$safeLocale|$safeTz';

                // 1. Seal WebRTC UDP/STUN/mDNS real-IP leaks
                var NoRtc = function() {
                  throw new DOMException('RTCPeerConnection disabled by privacy shield', 'NotAllowedError');
                };
                try {
                  Object.defineProperty(window, 'RTCPeerConnection', { value: NoRtc, configurable: true, writable: false });
                  Object.defineProperty(window, 'webkitRTCPeerConnection', { value: NoRtc, configurable: true, writable: false });
                  Object.defineProperty(window, 'mozRTCPeerConnection', { value: NoRtc, configurable: true, writable: false });
                } catch(e) {}
                if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                  try {
                    navigator.mediaDevices.getUserMedia = function(){ return Promise.reject(new DOMException('Disabled', 'NotAllowedError')); };
                  } catch(e) {}
                }

                // 2. Align browser timezone with egress IP region
                try {
                  Date.prototype.getTimezoneOffset = function(){ return $offset; };
                  if (window.Intl && Intl.DateTimeFormat) {
                    var origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
                    Intl.DateTimeFormat.prototype.resolvedOptions = function() {
                      var res = origResolved.apply(this, arguments);
                      try { res.timeZone = '$safeTz'; res.locale = '$safeLocale'; } catch(e) {}
                      return res;
                    };
                  }
                } catch(e) {}

                // 3. Align navigator.language / languages & hide automation flag
                try {
                  Object.defineProperty(navigator, 'language', { get: function(){ return '$safeLocale'; }, configurable: true });
                  Object.defineProperty(navigator, 'languages', { get: function(){ return ['$safeLocale', '$safeLangPrefix', 'en-US', 'en']; }, configurable: true });
                  Object.defineProperty(navigator, 'webdriver', { get: function(){ return false; }, configurable: true });
                } catch(e) {}

                // 4. Block GPS/Cell-tower Geolocation API so physical country cannot leak
                try {
                  if (navigator.geolocation) {
                    var denyGeo = function(s, e){ if (typeof e === 'function') e({ code: 1, message: 'Permission denied' }); };
                    navigator.geolocation.getCurrentPosition = denyGeo;
                    navigator.geolocation.watchPosition = function(){ return 0; };
                  }
                } catch(e) {}
              } catch(e) {}
            })();
        """.trimIndent()
    }

    fun getBlacklistedLionIps(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BLACKLIST_IPS, "").orEmpty()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toCollection(LinkedHashSet())
    }

    fun blacklistLionIp(context: Context, ip: String?) {
        val clean = ip?.trim().orEmpty()
        if (clean.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getBlacklistedLionIps(context).toMutableList()
        list.remove(clean)
        list.add(0, clean)
        while (list.size > MAX_BLACKLIST_IPS) list.removeAt(list.lastIndex)
        prefs.edit().putString(KEY_BLACKLIST_IPS, list.joinToString("\n")).apply()
    }

    fun enforceStrictBypass(context: Context) {
        val settings = TunnelConfig.load(context)
        val nextDoh = NO_ECS_DOH_URLS.let { list ->
            val idx = list.indexOf(settings.dohUrl)
            list[(idx + 1).mod(list.size)]
        }
        TunnelConfig.saveDohAndDns(context, dohUrl = nextDoh, primaryDns = "1.1.1.1")
        if (settings.mode != TunnelConfig.Mode.SMART && settings.mode != TunnelConfig.Mode.DNS_BLACKOUT) {
            TunnelConfig.saveMode(context, TunnelConfig.Mode.SMART)
        }
        VpnLayerStore.rotateToNextLayer(context)
    }

    /**
     * Unleashes the heavy **Brutal Lion** 4-round multi-hop & entry-laundering circumvention engine
     * for Google AI Studio (keeps [FlowWarriorEngine] 100% untouched).
     */
    fun unleashLionForAiStudio(
        context: Context,
        excludedEgressIps: Set<String> = emptySet(),
        excludedChainSpecs: Set<String> = emptySet(),
        excludeCurrentRoute: Boolean = false,
        onStep: (String) -> Unit = {}
    ): GeoBypassEngine.ChainDiscoveryResult {
        val netKey = runCatching { AutopilotBrain.detectNetworkContextKey(context) }.getOrDefault("default")
        val lionNetKey = "lion:$netKey"
        val combinedExcluded = (excludedEgressIps + getBlacklistedLionIps(context)).toMutableSet()

        onStep("🦁 [$LION_TITLE] Engaging heavy anti-DPI + multi-hop circumvention for AI Studio…")
        val result = GeoBypassEngine.discoverAndChainBestRoute(
            context = context,
            targetService = GeoBypassEngine.TargetService.AI_STUDIO,
            allowHarvest = true,
            maxCandidates = 64,
            excludedEgressIps = combinedExcluded,
            excludeCurrentRoute = excludeCurrentRoute,
            excludedChainSpecs = excludedChainSpecs,
            maxRounds = 4,
            onStep = onStep
        )

        val winnerEgress = result.report.egress
        val winnerIp = winnerEgress?.ip
        if (result.success && winnerEgress != null && !winnerIp.isNullOrBlank()) {
            val chainSpec = TunnelConfig.formatProxyChain(result.bestHops)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_CLEAN_CHAIN, chainSpec).apply()
            AutopilotBrain.ensureLoaded(context)
            AutopilotBrain.recordOutcome(
                contextKey = lionNetKey,
                armId = "lion:$winnerIp",
                protocolFamily = "lion-${result.bestHops.size}hop",
                success = true,
                rttMs = result.report.aiStudio.latencyMs.toDouble().coerceAtLeast(1.0),
                geoMaskObserved = AutopilotBrain.GEO_CLEAR_AISTUDIO,
                persistContext = context
            )
            onStep("🦁 [$LION_BADGE_PREFIX VICTORY] AI Studio unlocked via ${result.bestChainLabel} (${winnerEgress.countryCode} · $winnerIp)")
        } else if (!winnerIp.isNullOrBlank()) {
            blacklistLionIp(context, winnerIp)
            AutopilotBrain.ensureLoaded(context)
            AutopilotBrain.recordOutcome(
                contextKey = lionNetKey,
                armId = "lion:$winnerIp",
                protocolFamily = "lion-${result.bestHops.size.coerceAtLeast(1)}hop",
                success = false,
                rttMs = null,
                geoMaskObserved = 0,
                persistContext = context
            )
        }
        return result
    }
}
