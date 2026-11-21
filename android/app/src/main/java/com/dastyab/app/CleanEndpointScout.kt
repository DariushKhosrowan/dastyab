package com.dastyab.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * CleanEndpointScout — on-device IP Range Scout & Clean DNS/DoH scanner
 * inspired by WhiteDNS/IP-Range-Scout-Android and WhiteDNS-Android.
 *
 * Scans curated CDN edge IPv4 ranges (Cloudflare, Fastly, GCore) and clean DNS/DoH
 * resolvers in parallel so users under strict censorship can find working low-latency
 * edge IPs and unpoisoned DNS servers without any external tool.
 */
object CleanEndpointScout {

    data class EndpointResult(
        val address: String,
        val label: String,
        val latencyMs: Long,
        val reachable: Boolean,
        val kind: Kind
    )

    enum class Kind { EDGE_IP, UDP_DNS, DOH }

    data class DohPreset(
        val name: String,
        val url: String,
        val bootstrapIp: String
    )

    /** Curated DoH resolvers for Emergency DNS Blackout VPN mode. */
    val DOH_PRESETS = listOf(
        DohPreset("Cloudflare 1.1.1.1 (DoH)", "https://1.1.1.1/dns-query", "1.1.1.1"),
        DohPreset("Cloudflare 1.0.0.1 (DoH)", "https://1.0.0.1/dns-query", "1.0.0.1"),
        DohPreset("Google 8.8.8.8 (DoH)", "https://8.8.8.8/dns-query", "8.8.8.8"),
        DohPreset("Google 8.8.4.4 (DoH)", "https://8.8.4.4/dns-query", "8.8.4.4"),
        DohPreset("Quad9 9.9.9.9 (DoH)", "https://9.9.9.9/dns-query", "9.9.9.9"),
        DohPreset("AdGuard Unfiltered (DoH)", "https://94.140.14.140/dns-query", "94.140.14.140"),
        DohPreset("Cloudflare Security (DoH)", "https://1.1.1.2/dns-query", "1.1.1.2")
    )

    /** Curated Clean IPv4 DNS resolvers (WhiteDNS-style international & clean DNS pool). */
    val CLEAN_DNS_CANDIDATES = listOf(
        "1.1.1.1" to "Cloudflare Primary",
        "1.0.0.1" to "Cloudflare Secondary",
        "8.8.8.8" to "Google Primary",
        "8.8.4.4" to "Google Secondary",
        "9.9.9.9" to "Quad9 Primary",
        "149.112.112.112" to "Quad9 Secondary",
        "208.67.222.222" to "OpenDNS Primary",
        "94.140.14.14" to "AdGuard Primary",
        "76.76.2.0" to "ControlD",
        "4.2.2.4" to "Level3 Clean DNS"
    )

    /** Clean Cloudflare / CDN edge CIDRs for on-device IP Range Scouting. */
    val DEFAULT_EDGE_CIDRS = listOf(
        "104.16.0.0/16",
        "104.17.0.0/16",
        "104.18.0.0/16",
        "104.21.0.0/16",
        "172.64.0.0/16",
        "172.67.0.0/16",
        "162.159.0.0/16",
        "188.114.96.0/20"
    )

    enum class EdgeRegion(val code: String, val label: String) {
        US("US", "North America"),
        EU("EU", "Europe"),
        ASIA("ASIA", "Asia-Pacific"),
        GLOBAL("GLOBAL", "Global Anycast")
    }

    data class RegionalTarget(
        val id: String,
        val region: EdgeRegion,
        val label: String,
        val host: String,
        val port: Int,
        val dohUrl: String? = null
    )

    data class RegionBenchmarkResult(
        val target: RegionalTarget,
        val latencyMs: Long,
        val reachable: Boolean,
        val within300Ms: Boolean = reachable && latencyMs in 1..FailoverWatchdog.LATENCY_SWITCH_THRESHOLD_MS
    )

    /** Curated multi-region endpoints & DoH relays raced in parallel for sub-300ms route selection. */
    val MULTI_REGION_BENCHMARK_TARGETS: List<RegionalTarget> = listOf(
        RegionalTarget("us-cf", EdgeRegion.US, "US · Cloudflare Anycast", "1.1.1.1", 443, "https://1.1.1.1/dns-query"),
        RegionalTarget("us-google", EdgeRegion.US, "US · Google Edge", "8.8.8.8", 443, "https://8.8.8.8/dns-query"),
        RegionalTarget("us-opendns", EdgeRegion.US, "US · Cisco Umbrella", "208.67.222.222", 443, "https://doh.opendns.com/dns-query"),
        RegionalTarget("eu-quad9", EdgeRegion.EU, "EU · Quad9 Zurich", "9.9.9.9", 443, "https://9.9.9.9/dns-query"),
        RegionalTarget("eu-mullvad", EdgeRegion.EU, "EU · Mullvad Sweden", "194.242.2.2", 443, "https://dns.mullvad.net/dns-query"),
        RegionalTarget("eu-adguard", EdgeRegion.EU, "EU · AdGuard Cyprus/EU", "94.140.14.140", 443, "https://dns.adguard-dns.com/dns-query"),
        RegionalTarget("asia-cf", EdgeRegion.ASIA, "ASIA · Cloudflare PoP", "1.0.0.1", 443, "https://1.0.0.1/dns-query"),
        RegionalTarget("asia-google", EdgeRegion.ASIA, "ASIA · Google Secondary", "8.8.4.4", 443, "https://8.8.4.4/dns-query"),
        RegionalTarget("global-controld", EdgeRegion.GLOBAL, "GLOBAL · ControlD", "76.76.2.0", 443, "https://freedns.controld.com/p0")
    )

    /**
     * Pure-Kotlin CIDR sampler: generates up to [count] valid host IPv4 addresses
     * inside an IPv4 CIDR block (e.g. `104.16.0.0/16`).
     */
    fun sampleIpsFromCidr(cidr: String, count: Int, rng: Random = Random.Default): List<String> {
        val parts = cidr.trim().split('/')
        if (parts.size != 2) return emptyList()
        val ipParts = parts[0].split('.')
        val prefix = parts[1].toIntOrNull() ?: return emptyList()
        if (ipParts.size != 4 || prefix !in 8..30) return emptyList()

        var baseIp = 0L
        for (p in ipParts) {
            val octet = p.toIntOrNull() ?: return emptyList()
            if (octet !in 0..255) return emptyList()
            baseIp = (baseIp shl 8) or octet.toLong()
        }

        val hostBits = 32 - prefix
        val mask = (-1L shl hostBits) and 0xFFFFFFFFL
        val network = baseIp and mask
        val hostSpace = (1L shl hostBits) - 2L
        if (hostSpace <= 0) return emptyList()

        val result = LinkedHashSet<String>()
        val target = count.coerceAtMost(hostSpace.toInt().coerceAtMost(256))
        var attempts = 0
        while (result.size < target && attempts < target * 4) {
            val offset = rng.nextLong(1L, hostSpace + 1L)
            val candidate = network or offset
            val ip = "${(candidate ushr 24) and 255}.${(candidate ushr 16) and 255}.${(candidate ushr 8) and 255}.${candidate and 255}"
            result.add(ip)
            attempts++
        }
        return result.toList()
    }

    /**
     * Rewrites the host (and optional port) portion of a `vless://uuid@oldHost:port?...` URI
     * with a discovered Clean Edge IP while preserving the original SNI/Host header so CDN
     * routing continues working.
     */
    fun patchVlessWithCleanIp(vlessUri: String, cleanIp: String): String? {
        val parsedConfig = WorkerConfigStudio.parseVless(vlessUri) ?: return null
        val patched = parsedConfig.applyCleanIp(cleanIp, newName = parsedConfig.name) ?: return null
        return patched.toVlessUri()
    }

    /**
     * Tests a real TCP + TLS handshake against [connectIp]:[port] while sending [sniHost]
     * in the TLS SNI extension (so Cloudflare Workers edge routing is tested end-to-end).
     * Returns latency in milliseconds, or `-1L` on failure.
     */
    fun probeTlsWithSni(connectIp: String, port: Int, sniHost: String, timeoutMs: Int = 2500): Long {
        val cleanSni = sniHost.trim()
        if (cleanSni.isEmpty()) return probeTcpLatency(connectIp, port, timeoutMs)
        val start = System.nanoTime()
        return try {
            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            (factory.createSocket() as javax.net.ssl.SSLSocket).use { socket ->
                socket.soTimeout = timeoutMs
                if (android.os.Build.VERSION.SDK_INT >= 24 || android.os.Build.VERSION.SDK_INT == 0) {
                    val params = socket.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(cleanSni))
                    socket.sslParameters = params
                }
                socket.connect(InetSocketAddress(connectIp.trim(), port), timeoutMs)
                socket.startHandshake()
            }
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Pure-Kotlin /24 neighborhood sampler: expands proven winning [seedIps] into sibling IPs
     * inside their `/24` (and `/22` fallback) subnets so scouting exploits clean edge clusters first.
     */
    fun sampleNeighborhoodIps(
        seedIps: List<String>,
        count: Int,
        rng: Random = Random.Default
    ): List<String> {
        if (seedIps.isEmpty() || count <= 0) return emptyList()
        val result = LinkedHashSet<String>()
        val validSeeds = seedIps.mapNotNull { ip ->
            val parts = ip.trim().split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size == 4 && parts.all { it in 0..255 }) parts else null
        }
        if (validSeeds.isEmpty()) return emptyList()

        // Include seeds first, then generate /24 siblings
        validSeeds.forEach { p ->
            if (result.size < count) result.add("${p[0]}.${p[1]}.${p[2]}.${p[3]}")
        }
        var attempts = 0
        while (result.size < count && attempts < count * 5) {
            val base = validSeeds[attempts % validSeeds.size]
            val thirdOctet = if (attempts % 4 == 3) {
                ((base[2] + rng.nextInt(-2, 3)).coerceIn(0, 255))
            } else base[2]
            val host = rng.nextInt(1, 255)
            result.add("${base[0]}.${base[1]}.$thirdOctet.$host")
            attempts++
        }
        return result.toList()
    }

    /**
     * Smart Clean-IP Scout combining 60% `/24` neighborhood exploitation of [seedIps]
     * and 40% fresh CIDR exploration, verified with optional TLS + SNI handshake.
     */
    fun scoutSmartEdgeIps(
        seedIps: List<String>,
        cidrs: List<String> = DEFAULT_EDGE_CIDRS,
        totalBudget: Int = 36,
        sniHost: String = "",
        timeoutMs: Int = 1800
    ): List<EndpointResult> {
        val neighborBudget = if (seedIps.isNotEmpty()) (totalBudget * 6) / 10 else 0
        val exploreBudget = (totalBudget - neighborBudget).coerceAtLeast(8)

        val candidates = LinkedHashMap<String, String>()
        sampleNeighborhoodIps(seedIps, neighborBudget).forEach { ip ->
            candidates[ip] = "/24 Neighbor"
        }
        val perCidr = ((exploreBudget + cidrs.size - 1) / cidrs.size.coerceAtLeast(1)).coerceIn(1, 5)
        for (cidr in cidrs) {
            for (ip in sampleIpsFromCidr(cidr, perCidr)) {
                if (candidates.size >= totalBudget) break
                candidates.putIfAbsent(ip, "Edge ($cidr)")
            }
        }

        val pool = Executors.newFixedThreadPool(8)
        return try {
            val tasks = candidates.entries.map { (ip, label) ->
                Callable {
                    val latency = if (sniHost.isNotBlank()) {
                        probeTlsWithSni(ip, 443, sniHost, timeoutMs)
                    } else {
                        probeTcpLatency(ip, 443, timeoutMs)
                    }
                    EndpointResult(
                        address = ip,
                        label = label,
                        latencyMs = latency,
                        reachable = latency >= 0,
                        kind = Kind.EDGE_IP
                    )
                }
            }
            pool.invokeAll(tasks, 12, TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .filter { it.reachable }
                .sortedBy { it.latencyMs }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Scouts clean edge IPs across curated (or custom) CIDRs on TCP port 443. */
    fun scoutCleanEdgeIps(
        cidrs: List<String> = DEFAULT_EDGE_CIDRS,
        samplesPerCidr: Int = 3,
        timeoutMs: Int = 1800
    ): List<EndpointResult> {
        val candidates = cidrs.flatMap { cidr ->
            sampleIpsFromCidr(cidr, samplesPerCidr).map { ip -> ip to cidr }
        }
        val pool = Executors.newFixedThreadPool(8)
        return try {
            val tasks = candidates.map { (ip, cidr) ->
                Callable {
                    val latency = probeTcpLatency(ip, 443, timeoutMs)
                    EndpointResult(
                        address = ip,
                        label = "Edge ($cidr)",
                        latencyMs = latency,
                        reachable = latency >= 0,
                        kind = Kind.EDGE_IP
                    )
                }
            }
            pool.invokeAll(tasks, 12, TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .filter { it.reachable }
                .sortedBy { it.latencyMs }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Pure-Kotlin sorter ranking reachable multi-region benchmarks: sub-300ms SLA first, then lowest RTT. */
    fun rankRegionalBenchmarks(
        results: List<RegionBenchmarkResult>,
        latencyThresholdMs: Long = FailoverWatchdog.LATENCY_SWITCH_THRESHOLD_MS
    ): List<RegionBenchmarkResult> {
        return results
            .filter { it.reachable && it.latencyMs > 0L }
            .map { it.copy(within300Ms = it.latencyMs <= latencyThresholdMs) }
            .sortedWith(compareBy<RegionBenchmarkResult> { !it.within300Ms }.thenBy { it.latencyMs }.thenBy { it.target.region.ordinal })
    }

    /** Pure-Kotlin per-region fastest benchmark map (for UI / Autopilot multi-region telemetry). */
    fun summarizeFastestByRegion(results: List<RegionBenchmarkResult>): Map<EdgeRegion, RegionBenchmarkResult> {
        val ranked = rankRegionalBenchmarks(results)
        val map = LinkedHashMap<EdgeRegion, RegionBenchmarkResult>()
        for (res in ranked) {
            map.putIfAbsent(res.target.region, res)
        }
        return map
    }

    /** Formats a compact multi-region latency digest (e.g. `US 84ms | EU 112ms | ASIA 210ms`). */
    fun formatRegionBenchmarkSummary(results: List<RegionBenchmarkResult>): String {
        val byRegion = summarizeFastestByRegion(results)
        if (byRegion.isEmpty()) return "No reachable regions"
        return byRegion.entries.joinToString(" | ") { (region, res) ->
            val mark = if (res.within300Ms) "✓" else "!"
            "${region.code} ${res.latencyMs}ms$mark"
        }
    }

    /**
     * Races TCP/443 & DoH health probes across multiple global edge regions in parallel
     * so Autopilot always knows the lowest-latency sub-300ms region & DoH endpoint.
     */
    fun benchmarkMultiRegionParallel(
        targets: List<RegionalTarget> = MULTI_REGION_BENCHMARK_TARGETS,
        timeoutMs: Int = 1800,
        latencyThresholdMs: Long = FailoverWatchdog.LATENCY_SWITCH_THRESHOLD_MS
    ): List<RegionBenchmarkResult> {
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(targets.size.coerceIn(2, 9))
        val completion = ExecutorCompletionService<RegionBenchmarkResult>(pool)
        val futures = ArrayList<Future<RegionBenchmarkResult>>(targets.size)
        return try {
            for (target in targets) {
                futures.add(
                    completion.submit(Callable {
                        val ms = if (!target.dohUrl.isNullOrBlank()) {
                            val dohMs = probeDohLatency(target.dohUrl, timeoutMs)
                            if (dohMs > 0L) dohMs else probeTcpLatency(target.host, target.port, timeoutMs)
                        } else {
                            probeTcpLatency(target.host, target.port, timeoutMs)
                        }
                        val ok = ms > 0L
                        RegionBenchmarkResult(
                            target = target,
                            latencyMs = ms,
                            reachable = ok,
                            within300Ms = ok && ms <= latencyThresholdMs
                        )
                    })
                )
            }
            val collected = ArrayList<RegionBenchmarkResult>(targets.size)
            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs + 800L)
            for (i in targets.indices) {
                val remNs = (deadlineNs - System.nanoTime()).coerceAtLeast(1L)
                val done = completion.poll(remNs, TimeUnit.NANOSECONDS) ?: break
                runCatching { done.get() }.getOrNull()?.let { collected.add(it) }
            }
            rankRegionalBenchmarks(collected, latencyThresholdMs)
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    /** Scouts clean UDP DNS and DoH resolvers and ranks reachable ones by latency. */
    fun scoutCleanDnsAndDoh(timeoutMs: Int = 2200): List<EndpointResult> {
        val pool = Executors.newFixedThreadPool(8)
        return try {
            val tasks = mutableListOf<Callable<EndpointResult>>()
            CLEAN_DNS_CANDIDATES.forEach { (ip, name) ->
                tasks.add(Callable {
                    val latency = probeUdpDnsLatency(ip, timeoutMs)
                    EndpointResult(ip, "$name (UDP 53)", latency, latency >= 0, Kind.UDP_DNS)
                })
            }
            DOH_PRESETS.forEach { preset ->
                tasks.add(Callable {
                    val latency = probeDohLatency(preset.url, timeoutMs)
                    EndpointResult(preset.url, preset.name, latency, latency >= 0, Kind.DOH)
                })
            }
            pool.invokeAll(tasks, 12, TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .filter { it.reachable }
                .sortedBy { it.latencyMs }
        } finally {
            pool.shutdownNow()
        }
    }

    fun probeTcpLatency(host: String, port: Int, timeoutMs: Int): Long {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
        } catch (_: Exception) {
            -1L
        }
    }

    /** Sends a minimal standard DNS query for `cloudflare.com` over UDP/53 to verify live DNS. */
    fun probeUdpDnsLatency(dnsIp: String, timeoutMs: Int): Long {
        val query = buildMinimalDnsQuery()
        val start = System.nanoTime()
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val addr = InetAddress.getByName(dnsIp)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val respBuf = ByteArray(512)
                val resp = DatagramPacket(respBuf, respBuf.size)
                socket.receive(resp)
                if (resp.length >= 12) {
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                } else {
                    -1L
                }
            }
        } catch (_: Exception) {
            -1L
        }
    }

    /** Sends an RFC 8484 DNS-over-HTTPS POST query (`application/dns-message`). */
    fun probeDohLatency(dohUrl: String, timeoutMs: Int): Long {
        val query = buildMinimalDnsQuery()
        val start = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(dohUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }
            conn.outputStream.use { it.write(query) }
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size >= 12) {
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                } else -1L
            } else -1L
        } catch (_: Exception) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    /** Minimal RFC 1035 DNS A-record query for `one.one.one.one`. */
    fun buildMinimalDnsQuery(): ByteArray = byteArrayOf(
        0x12, 0x34, // ID
        0x01, 0x00, // Standard query, recursion desired
        0x00, 0x01, // QDCOUNT = 1
        0x00, 0x00, // ANCOUNT = 0
        0x00, 0x00, // NSCOUNT = 0
        0x00, 0x00, // ARCOUNT = 0
        0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
        0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
        0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
        0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
        0x00,       // Root label
        0x00, 0x01, // QTYPE = A
        0x00, 0x01  // QCLASS = IN
    )
}
