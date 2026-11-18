package com.dastyab.app

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * NetworkTools — pure-Kotlin on-device anti-censorship & network power utilities:
 *  • Cloudflare Exit IP / Geo / ASN / TLS Trace (`/cdn-cgi/trace`)
 *  • DNS Poisoning Detector (compares System DNS vs Encrypted DoH)
 *  • TLS / SNI Handshake & Certificate Inspector (exposes DPI/MITM spoofing)
 *  • Base64 Subscription Encoder / Decoder
 */
object NetworkTools {

    data class TraceInfo(
        val ip: String,
        val location: String,
        val colo: String,
        val tls: String,
        val http: String,
        val warp: String,
        val rawFields: Map<String, String>
    )

    data class DnsCompareResult(
        val domain: String,
        val systemIps: List<String>,
        val dohReachable: Boolean,
        val dohLatencyMs: Long,
        val likelyPoisoned: Boolean,
        val verdict: String
    )

    data class TlsInspectionResult(
        val host: String,
        val port: Int,
        val connected: Boolean,
        val latencyMs: Long,
        val protocol: String,
        val cipherSuite: String,
        val certSubject: String,
        val certIssuer: String,
        val error: String? = null
    )

    /** Pure-Kotlin parser for Cloudflare `/cdn-cgi/trace` key=value output. */
    fun parseCloudflareTrace(body: String): TraceInfo {
        val map = LinkedHashMap<String, String>()
        body.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        return TraceInfo(
            ip = map["ip"] ?: "unknown",
            location = map["loc"] ?: "??",
            colo = map["colo"] ?: "??",
            tls = map["tls"] ?: "unknown",
            http = map["http"] ?: "unknown",
            warp = map["warp"] ?: "off",
            rawFields = map
        )
    }

    /** Fetches current exit IP & geo info from Cloudflare trace (`https://1.1.1.1/cdn-cgi/trace`). */
    fun fetchExitTrace(timeoutMs: Int = 4500): TraceInfo? {
        val urls = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://cloudflare.com/cdn-cgi/trace"
        )
        for (u in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", "DastYab/1.8")
                }
                if (conn.responseCode == 200) {
                    val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    return parseCloudflareTrace(text)
                }
            } catch (_: Exception) {
                // try next
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    /**
     * Checks if System DNS returns RFC1918/loopback/bogon sinkhole IPs (common DNS poisoning sign)
     * and compares against encrypted DoH reachability.
     */
    fun checkDnsPoisoning(domain: String, dohUrl: String = TunnelConfig.DEFAULT_DOH_URL): DnsCompareResult {
        val cleanDomain = domain.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val systemIps = try {
            InetAddress.getAllByName(cleanDomain).mapNotNull { it.hostAddress }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
        val dohMs = CleanEndpointScout.probeDohLatency(dohUrl, 2500)
        val hasBogon = systemIps.any { isSinkholeOrPrivateIp(it) }
        val likelyPoisoned = hasBogon || (systemIps.isEmpty() && dohMs >= 0)
        val verdict = when {
            hasBogon -> "POISONED! System DNS returned sinkhole/private IP (${systemIps.joinToString()}). Enable DNS Blackout VPN (DoH)."
            systemIps.isEmpty() && dohMs >= 0 -> "System DNS blocked, but DoH works (${dohMs}ms)! Activate DNS Blackout VPN."
            systemIps.isEmpty() -> "Both System DNS and DoH failed — severe blackout or offline."
            else -> "System DNS resolved cleanly (${systemIps.joinToString()}); DoH=${if (dohMs >= 0) "${dohMs}ms" else "blocked"}."
        }
        return DnsCompareResult(
            domain = cleanDomain,
            systemIps = systemIps,
            dohReachable = dohMs >= 0,
            dohLatencyMs = dohMs,
            likelyPoisoned = likelyPoisoned,
            verdict = verdict
        )
    }

    /** Pure-Kotlin detector for common DNS censorship sinkhole / bogon IPs (`10.10.34.*`, `0.0.0.0`, `127.*`, RFC1918). */
    fun isSinkholeOrPrivateIp(ip: String): Boolean {
        val t = ip.trim()
        return t == "0.0.0.0" ||
            t.startsWith("127.") ||
            t.startsWith("10.") ||
            t.startsWith("192.168.") ||
            t.startsWith("169.254.") ||
            t == "::1" ||
            t == "::"
    }

    /** Performs a real TLS handshake to [host]:[port] and inspects protocol, cipher, and peer certificate. */
    fun inspectTlsHandshake(host: String, port: Int = 443, timeoutMs: Int = 3500): TlsInspectionResult {
        val cleanHost = host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        val start = System.nanoTime()
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                socket.startHandshake()
                val latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                val session = socket.session
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                TlsInspectionResult(
                    host = cleanHost,
                    port = port,
                    connected = true,
                    latencyMs = latency,
                    protocol = session.protocol ?: "unknown",
                    cipherSuite = session.cipherSuite ?: "unknown",
                    certSubject = cert?.subjectX500Principal?.name ?: "unknown",
                    certIssuer = cert?.issuerX500Principal?.name ?: "unknown"
                )
            }
        } catch (e: Exception) {
            TlsInspectionResult(
                host = cleanHost,
                port = port,
                connected = false,
                latencyMs = -1L,
                protocol = "-",
                cipherSuite = "-",
                certSubject = "-",
                certIssuer = "-",
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }

    /** Pure-Kotlin Base64 encoder (Java 8+ JVM & Android API 24+ safe). */
    fun encodeBase64(plain: String): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(bytes)
        } else {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    /** Pure-Kotlin Base64 decoder (supports standard and URL-safe Base64 on JVM & Android API 24+). */
    fun decodeBase64(encoded: String): String? {
        val compact = encoded.trim().replace("\r", "").replace("\n", "")
        if (compact.isEmpty()) return null
        return try {
            val bytes = if (Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
}
