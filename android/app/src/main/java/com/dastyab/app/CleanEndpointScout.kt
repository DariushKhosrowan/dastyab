package com.dastyab.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
     * Rewrites the host portion of a `vless://uuid@oldHost:port?...` URI with a discovered
     * Clean Edge IP while preserving the original SNI/Host header so CDN routing continues working.
     */
    fun patchVlessWithCleanIp(vlessUri: String, cleanIp: String): String? {
        if (!TunnelConfig.isValidIpv4(cleanIp)) return null
        val parsed = TunnelConfig.parseVlessUri(vlessUri) ?: return null
        val atIdx = vlessUri.indexOf('@')
        if (atIdx < 0) return null
        val afterAt = vlessUri.substring(atIdx + 1)
        val queryOrFragIdx = afterAt.indexOfAny(charArrayOf('?', '#'))
        val authority = if (queryOrFragIdx >= 0) afterAt.substring(0, queryOrFragIdx) else afterAt
        val suffix = if (queryOrFragIdx >= 0) afterAt.substring(queryOrFragIdx) else ""
        val colonIdx = authority.lastIndexOf(':')
        val portPart = if (colonIdx >= 0) authority.substring(colonIdx) else ":${parsed.port}"
        return vlessUri.substring(0, atIdx + 1) + cleanIp.trim() + portPart + suffix
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
