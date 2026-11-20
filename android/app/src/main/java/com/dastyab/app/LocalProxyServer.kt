package com.dastyab.app

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * LocalProxyServer — pure-Kotlin loopback proxy bridge listening on `127.0.0.1:<port>`.
 *
 * Accepts BOTH **HTTP (`CONNECT` + standard HTTP proxy requests)** AND **SOCKS5 (`NOAUTH` TCP)**
 * on the SAME port (via 1-byte protocol detection):
 *  • Other apps like v2rayNG, Chrome, NekoBox, or Android Wi-Fi Proxy can point at `127.0.0.1:<port>`
 *    (either SOCKS5 or HTTP).
 *  • WebView (`ProxyController`) and `VpnService.setHttpProxy` require an HTTP CONNECT proxy even
 *    when your upstream tool only speaks SOCKS5 (e.g., `127.0.0.1:10808` or Orbot `9050`).
 *    Setting [Upstream.Socks5] turns DastYab into an instant HTTP -> SOCKS5 translator!
 *  • Setting [Upstream.Direct] routes outbound TCP sockets through the active OS network / system
 *    VPN (such as Lantern running on Android) for clean multi-layer stacking without conflicting
 *    with the OS `VpnService` slot.
 */
class LocalProxyServer(
    requestedPort: Int = 10850,
    val upstream: Upstream = Upstream.Direct,
    private val connectTimeoutMs: Int = 8000,
    private val socketReadTimeoutMs: Int = 120_000,
    private val enableDpiDesync: Boolean = true
) : Closeable {

    sealed class Upstream {
        object Direct : Upstream() {
            override fun describe(): String = "Direct / Active System VPN"
        }

        data class Http(
            val host: String,
            val port: Int,
            val username: String = "",
            val password: String = ""
        ) : Upstream() {
            override fun describe(): String = "HTTP $host:$port"
        }

        data class Socks5(
            val host: String,
            val port: Int,
            val username: String = "",
            val password: String = ""
        ) : Upstream() {
            override fun describe(): String = "SOCKS5 $host:$port"
        }

        data class Chain(val hops: List<Upstream>) : Upstream() {
            fun flattenedHops(): List<Upstream> = hops.flatMap {
                when (it) {
                    is Direct -> emptyList()
                    is Chain -> it.flattenedHops()
                    else -> listOf(it)
                }
            }

            override fun describe(): String {
                val flat = flattenedHops()
                return if (flat.isEmpty()) {
                    Direct.describe()
                } else {
                    "${flat.size}-Hop Chain (${flat.joinToString(" → ") { it.describe() }})"
                }
            }
        }

        /** Ordered failover list of upstreams/chains; tries each route sequentially on dial failure. */
        data class Fallback(val alternatives: List<Upstream>) : Upstream() {
            override fun describe(): String =
                if (alternatives.isEmpty()) Direct.describe()
                else "Failover(${alternatives.size} routes: ${alternatives.first().describe()})"
        }

        abstract fun describe(): String
    }

    data class Stats(
        val running: Boolean,
        val port: Int,
        val upstreamLabel: String,
        val activeConnections: Int,
        val totalConnections: Long,
        val bytesTransferred: Long,
        val lastTarget: String,
        val lastError: String?
    )

    private val desiredPort = requestedPort
    private val running = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)
    private val totalConnections = AtomicLong(0)
    private val bytesTransferred = AtomicLong(0)
    @Volatile private var lastTarget: String = ""
    @Volatile private var lastError: String? = null

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workerPool: ExecutorService? = null

    @Volatile
    var actualPort: Int = -1
        private set

    fun isRunning(): Boolean = running.get() && serverSocket?.isClosed == false

    fun stats(): Stats = Stats(
        running = isRunning(),
        port = actualPort,
        upstreamLabel = upstream.describe(),
        activeConnections = activeConnections.get(),
        totalConnections = totalConnections.get(),
        bytesTransferred = bytesTransferred.get(),
        lastTarget = lastTarget,
        lastError = lastError
    )

    @Throws(IOException::class)
    fun start(): Int {
        if (isRunning()) return actualPort
        val loopback = InetAddress.getByName("127.0.0.1")
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(loopback, desiredPort), 64)
        serverSocket = ss
        actualPort = ss.localPort
        running.set(true)
        workerPool = Executors.newCachedThreadPool { r ->
            Thread(r, "dastyab-proxy-worker").apply { isDaemon = true }
        }
        acceptThread = thread(isDaemon = true, name = "dastyab-proxy-accept") {
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
                val pool = workerPool
                if (pool == null || pool.isShutdown) {
                    runCatching { client.close() }
                    break
                }
                pool.execute { handleClient(client) }
            }
        }
        return actualPort
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        workerPool?.shutdownNow()
        workerPool = null
    }

    private fun handleClient(client: Socket) {
        totalConnections.incrementAndGet()
        activeConnections.incrementAndGet()
        var remote: Socket? = null
        try {
            client.soTimeout = socketReadTimeoutMs
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 8192)
            val output = client.getOutputStream()

            input.mark(1)
            val firstByte = input.read()
            if (firstByte < 0) return
            input.reset()

            remote = if (firstByte == 0x05) {
                handleSocks5Client(input, output)
            } else {
                handleHttpClient(input, output)
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
        } finally {
            runCatching { remote?.close() }
            runCatching { client.close() }
            activeConnections.decrementAndGet()
        }
    }

    private fun handleSocks5Client(input: BufferedInputStream, output: OutputStream): Socket? {
        val ver = input.read()
        val nMethods = input.read()
        if (ver != 0x05 || nMethods <= 0) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return null
        }
        val methods = readExact(input, nMethods) ?: return null
        if (!methods.contains(0x00.toByte())) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return null
        }
        // Accept NO AUTHENTICATION REQUIRED (0x00)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val header = readExact(input, 4) ?: return null
        if (header[0] != 0x05.toByte()) return null
        val cmd = header[1].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF
        if (cmd != 0x01) {
            // Command not supported (only TCP CONNECT 0x01)
            sendSocks5Reply(output, 0x07)
            return null
        }

        val targetHost = when (atyp) {
            0x01 -> {
                val addr = readExact(input, 4) ?: return null
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            0x03 -> {
                val len = input.read()
                if (len <= 0) return null
                val nameBytes = readExact(input, len) ?: return null
                String(nameBytes, StandardCharsets.US_ASCII)
            }
            0x04 -> {
                val addr = readExact(input, 16) ?: return null
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            else -> {
                sendSocks5Reply(output, 0x08)
                return null
            }
        }
        val portBytes = readExact(input, 2) ?: return null
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        if (targetPort !in 1..65535) {
            sendSocks5Reply(output, 0x04)
            return null
        }

        lastTarget = "$targetHost:$targetPort"
        val remote = try {
            openUpstreamSocket(targetHost, targetPort)
        } catch (e: IOException) {
            lastError = "${e.message ?: e.javaClass.simpleName} ($targetHost:$targetPort)"
            sendSocks5Reply(output, 0x05)
            return null
        }
        sendSocks5Reply(output, 0x00)
        relayBidirectional(input, output, remote)
        return remote
    }

    private fun handleHttpClient(input: BufferedInputStream, output: OutputStream): Socket? {
        val headerLines = readHttpHeaders(input) ?: return null
        if (headerLines.isEmpty()) return null
        val requestLine = headerLines.first()
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null

        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts[2]

        if (method == "CONNECT") {
            val (host, port) = parseHostPort(target, 443) ?: run {
                writeHttpError(output, 400, "Bad Request")
                return null
            }
            lastTarget = "$host:$port"
            val remote = try {
                openUpstreamSocket(host, port)
            } catch (e: IOException) {
                lastError = "${e.message ?: e.javaClass.simpleName} ($host:$port)"
                writeHttpError(output, 502, "Bad Gateway")
                return null
            }
            output.write("$version 200 Connection Established\r\nProxy-Agent: DastYab\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            relayBidirectional(input, output, remote)
            return remote
        }

        // Plain HTTP proxy request (e.g. GET http://example.com/path HTTP/1.1)
        val uri = try {
            URI(target)
        } catch (_: Exception) {
            writeHttpError(output, 400, "Bad Request")
            return null
        }
        val host = uri.host ?: headerLines
            .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(':')
            ?: run {
                writeHttpError(output, 400, "Missing Host")
                return null
            }
        val port = if (uri.port in 1..65535) uri.port else 80
        val pathAndQuery = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        lastTarget = "$host:$port"

        val remote = try {
            openUpstreamSocket(host, port)
        } catch (e: IOException) {
            lastError = "${e.message ?: e.javaClass.simpleName} ($host:$port)"
            writeHttpError(output, 502, "Bad Gateway")
            return null
        }

        val remoteOut = remote.getOutputStream()
        val rewritten = StringBuilder()
        rewritten.append("$method $pathAndQuery $version\r\n")
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            if (line.startsWith("Proxy-Connection:", ignoreCase = true) ||
                line.startsWith("Proxy-Authorization:", ignoreCase = true)
            ) {
                continue
            }
            rewritten.append(line).append("\r\n")
        }
        rewritten.append("Connection: close\r\n\r\n")
        remoteOut.write(rewritten.toString().toByteArray(StandardCharsets.ISO_8859_1))
        remoteOut.flush()

        relayBidirectional(input, output, remote)
        return remote
    }

    @Throws(IOException::class)
    private fun openUpstreamSocket(targetHost: String, targetPort: Int): Socket {
        if (upstream is Upstream.Fallback) {
            val candidates = upstream.alternatives.ifEmpty { listOf(Upstream.Direct) }
            var lastErr: IOException? = null
            for (alt in candidates) {
                try {
                    return openTunneledSocket(alt, targetHost, targetPort)
                } catch (e: IOException) {
                    lastErr = e
                }
            }
            throw (lastErr ?: IOException("All fallback proxy routes failed"))
        }
        return openTunneledSocket(upstream, targetHost, targetPort)
    }

    @Throws(IOException::class)
    private fun openTunneledSocket(selectedUpstream: Upstream, targetHost: String, targetPort: Int): Socket {
        val hops = when (selectedUpstream) {
            is Upstream.Direct -> emptyList()
            is Upstream.Chain -> selectedUpstream.flattenedHops()
            is Upstream.Fallback -> selectedUpstream.alternatives.firstOrNull()?.let {
                when (it) {
                    is Upstream.Direct -> emptyList()
                    is Upstream.Chain -> it.flattenedHops()
                    else -> listOf(it)
                }
            } ?: emptyList()
            else -> listOf(selectedUpstream)
        }
        if (hops.isEmpty()) {
            guardSelfLoop(targetHost, targetPort)
            return Socket().apply {
                tcpNoDelay = true
                soTimeout = socketReadTimeoutMs
                connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
            }
        }

        val (firstHost, firstPort) = hopHostPort(hops[0])
        guardSelfLoop(firstHost, firstPort)
        val sock = Socket().apply {
            tcpNoDelay = true
            soTimeout = connectTimeoutMs
            connect(InetSocketAddress(firstHost, firstPort), connectTimeoutMs)
        }
        try {
            for (i in hops.indices) {
                val hop = hops[i]
                val (nextHost, nextPort) = if (i + 1 < hops.size) {
                    hopHostPort(hops[i + 1])
                } else {
                    targetHost to targetPort
                }
                if (i + 1 < hops.size) {
                    guardSelfLoop(nextHost, nextPort)
                }
                try {
                    when (hop) {
                        is Upstream.Http -> handshakeHttpConnect(sock, hop, nextHost, nextPort)
                        is Upstream.Socks5 -> handshakeSocks5Connect(sock, hop, nextHost, nextPort)
                        else -> throw IOException("Unsupported chain hop: ${hop.describe()}")
                    }
                } catch (e: IOException) {
                    throw IOException(
                        "Hop ${i + 1}/${hops.size} (${hop.describe()} → $nextHost:$nextPort): ${e.message ?: e.javaClass.simpleName}",
                        e
                    )
                }
            }
            sock.soTimeout = socketReadTimeoutMs
            return sock
        } catch (e: IOException) {
            runCatching { sock.close() }
            throw e
        }
    }

    private fun guardSelfLoop(host: String, port: Int) {
        val norm = host.trim().lowercase()
        if ((norm == "127.0.0.1" || norm == "localhost" || norm == "::1") && actualPort > 0 && port == actualPort) {
            throw IOException("Proxy self-loop blocked ($host:$port)")
        }
    }

    private fun hopHostPort(hop: Upstream): Pair<String, Int> = when (hop) {
        is Upstream.Http -> hop.host to hop.port
        is Upstream.Socks5 -> hop.host to hop.port
        else -> throw IOException("No endpoint for hop ${hop.describe()}")
    }

    @Throws(IOException::class)
    private fun handshakeHttpConnect(sock: Socket, hop: Upstream.Http, targetHost: String, targetPort: Int) {
        val out = sock.getOutputStream()
        val authority = if (targetHost.contains(':') && !targetHost.startsWith("[")) {
            "[$targetHost]:$targetPort"
        } else {
            "$targetHost:$targetPort"
        }
        val sb = StringBuilder()
            .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            .append("Host: ").append(authority).append("\r\n")
        if (hop.username.isNotEmpty() || hop.password.isNotEmpty()) {
            val token = encodeBase64("${hop.username}:${hop.password}".toByteArray(StandardCharsets.UTF_8))
            sb.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
        }
        sb.append("Proxy-Connection: keep-alive\r\n\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        out.flush()

        val respLines = readHttpHeaders(sock.getInputStream())
            ?: throw IOException("Empty response from upstream HTTP proxy")
        val statusLine = respLines.firstOrNull().orEmpty().trim()
        val code = statusLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
        if (code !in 200..299) {
            throw IOException("Upstream HTTP proxy rejected CONNECT: $statusLine")
        }
    }

    @Throws(IOException::class)
    private fun handshakeSocks5Connect(sock: Socket, hop: Upstream.Socks5, targetHost: String, targetPort: Int) {
        val out = sock.getOutputStream()
        val ins = sock.getInputStream()
        val hasAuth = hop.username.isNotEmpty() || hop.password.isNotEmpty()
        if (hasAuth) {
            // Offer NOAUTH (0x00) and USERNAME/PASSWORD (0x02)
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val authReply = readExact(ins, 2)
            ?: throw IOException("Upstream SOCKS5 EOF during greeting")
        if (authReply[0] != 0x05.toByte()) {
            throw IOException("Upstream SOCKS5 bad version (${authReply[0].toInt() and 0xFF})")
        }
        when (val method = authReply[1].toInt() and 0xFF) {
            0x00 -> { /* no auth required */ }
            0x02 -> {
                if (!hasAuth) throw IOException("Upstream SOCKS5 requires username/password")
                val uBytes = hop.username.toByteArray(StandardCharsets.UTF_8)
                val pBytes = hop.password.toByteArray(StandardCharsets.UTF_8)
                if (uBytes.size > 255 || pBytes.size > 255) {
                    throw IOException("SOCKS5 credentials exceed 255 bytes")
                }
                val authReq = ByteArray(3 + uBytes.size + pBytes.size)
                authReq[0] = 0x01
                authReq[1] = uBytes.size.toByte()
                System.arraycopy(uBytes, 0, authReq, 2, uBytes.size)
                authReq[2 + uBytes.size] = pBytes.size.toByte()
                System.arraycopy(pBytes, 0, authReq, 3 + uBytes.size, pBytes.size)
                out.write(authReq)
                out.flush()
                val userPassRep = readExact(ins, 2)
                    ?: throw IOException("Upstream SOCKS5 EOF during auth")
                if (userPassRep[1] != 0x00.toByte()) {
                    throw IOException("Upstream SOCKS5 username/password rejected")
                }
            }
            else -> throw IOException("Upstream SOCKS5 auth rejected ($method)")
        }

        val cleanHost = targetHost.trim().removePrefix("[").removeSuffix("]")
        val req = if (TunnelConfig.isValidIpv4(cleanHost)) {
            val ipParts = cleanHost.split('.')
            byteArrayOf(
                0x05,
                0x01,
                0x00,
                0x01, // IPV4
                ipParts[0].toInt().toByte(),
                ipParts[1].toInt().toByte(),
                ipParts[2].toInt().toByte(),
                ipParts[3].toInt().toByte(),
                ((targetPort shr 8) and 0xFF).toByte(),
                (targetPort and 0xFF).toByte()
            )
        } else {
            val hostBytes = cleanHost.toByteArray(StandardCharsets.US_ASCII)
            if (hostBytes.size > 255) throw IOException("Target host too long for SOCKS5")
            ByteArray(7 + hostBytes.size).apply {
                this[0] = 0x05
                this[1] = 0x01 // CONNECT
                this[2] = 0x00 // RSV
                this[3] = 0x03 // DOMAINNAME (remote DNS resolution on upstream proxy)
                this[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, this, 5, hostBytes.size)
                this[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
                this[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            }
        }
        out.write(req)
        out.flush()

        val repHead = readExact(ins, 4)
            ?: throw IOException("Upstream SOCKS5 EOF during CONNECT")
        if (repHead[0] != 0x05.toByte() || repHead[1] != 0x00.toByte()) {
            throw IOException("Upstream SOCKS5 CONNECT failed (code=${repHead[1].toInt() and 0xFF})")
        }
        when (repHead[3].toInt() and 0xFF) {
            0x01 -> readExact(ins, 4 + 2)
            0x04 -> readExact(ins, 16 + 2)
            0x03 -> {
                val dLen = ins.read()
                when {
                    dLen < 0 -> null
                    dLen > 0 -> readExact(ins, dLen + 2)
                    else -> readExact(ins, 2)
                }
            }
            else -> throw IOException("Upstream SOCKS5 unknown address type")
        } ?: throw IOException("Upstream SOCKS5 truncated bind address")
    }

    private fun encodeBase64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(alphabet[b0 ushr 2])
            sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            sb.append(if (i + 1 < bytes.size) alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun relayBidirectional(clientIn: InputStream, clientOut: OutputStream, remote: Socket) {
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()
        val up = thread(isDaemon = true, name = "dastyab-pipe-up") {
            copyStreamWithOptionalDesync(clientIn, remoteOut)
            runCatching { remote.shutdownOutput() }
        }
        copyStream(remoteIn, clientOut)
        runCatching { clientOut.flush() }
        runCatching { up.join(1500) }
    }

    private fun copyStreamWithOptionalDesync(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(16 * 1024)
        var isFirstWrite = true
        try {
            while (running.get()) {
                val n = src.read(buf)
                if (n < 0) break
                if (n > 0) {
                    if (isFirstWrite && enableDpiDesync && isTlsClientHello(buf, n)) {
                        writeTlsClientHelloFragmented(dst, buf, n)
                    } else {
                        dst.write(buf, 0, n)
                        dst.flush()
                    }
                    isFirstWrite = false
                    bytesTransferred.addAndGet(n.toLong())
                }
            }
        } catch (_: IOException) {
            // Normal on stream close or socket reset.
        }
    }

    private fun copyStream(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (running.get()) {
                val n = src.read(buf)
                if (n < 0) break
                if (n > 0) {
                    dst.write(buf, 0, n)
                    dst.flush()
                    bytesTransferred.addAndGet(n.toLong())
                }
            }
        } catch (_: IOException) {
            // Normal on stream close or socket reset.
        }
    }

    private fun sendSocks5Reply(out: OutputStream, rep: Int) {
        out.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun writeHttpError(out: OutputStream, code: Int, reason: String) {
        val body = "HTTP/1.1 $code $reason\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(body.toByteArray(StandardCharsets.US_ASCII))
            out.flush()
        }
    }

    private fun readHttpHeaders(input: InputStream): List<String>? {
        val lines = mutableListOf<String>()
        val cur = ByteArrayOutputStream(128)
        var prev = -1
        var totalBytes = 0
        while (totalBytes < 32_768) {
            val b = input.read()
            if (b < 0) return if (lines.isEmpty()) null else lines
            totalBytes++
            if (b == '\n'.code) {
                val raw = cur.toByteArray()
                cur.reset()
                val line = String(raw, StandardCharsets.ISO_8859_1).trimEnd('\r')
                if (line.isEmpty()) {
                    return lines
                }
                lines.add(line)
            } else if (b != '\r'.code || prev == '\r'.code) {
                cur.write(b)
            }
            prev = b
        }
        return lines
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n < 0) return null
            offset += n
        }
        return buf
    }

    data class HopTraceResult(
        val hop: TunnelConfig.ProxyHop,
        val latencyMs: Long,
        val exitIp: String,
        val countryCode: String,
        val warpStatus: String = "off"
    )

    companion object {
        /** Returns true when [buf] starts with a genuine TLS ClientHello record (`0x16 0x03`, handshake type `0x01`). */
        fun isTlsClientHello(buf: ByteArray, length: Int = buf.size): Boolean {
            if (length < 43 || length > buf.size) return false
            val b0 = buf[0].toInt() and 0xFF
            val b1 = buf[1].toInt() and 0xFF
            val hsType = buf[5].toInt() and 0xFF
            return b0 == 0x16 && b1 == 0x03 && hsType == 0x01
        }

        /**
         * Splits a TLS ClientHello length into micro-TCP segment `[start, end)` ranges that break
         * the TLS record header and SNI hostname across distinct TCP flushes to defeat stateless SNI DPI.
         */
        fun splitTlsClientHelloSlices(length: Int): List<IntRange> {
            if (length <= 0) return emptyList()
            if (length <= 8) return listOf(0 until length)
            val cuts = listOf(2, 6, 15, (length / 2).coerceAtLeast(24)).filter { it in 1 until length }.distinct().sorted()
            val out = ArrayList<IntRange>(cuts.size + 1)
            var prev = 0
            for (c in cuts) {
                if (c > prev) {
                    out.add(prev until c)
                    prev = c
                }
            }
            if (prev < length) out.add(prev until length)
            return out
        }

        private fun writeTlsClientHelloFragmented(dst: OutputStream, buf: ByteArray, len: Int) {
            val slices = splitTlsClientHelloSlices(len)
            for ((idx, range) in slices.withIndex()) {
                dst.write(buf, range.first, range.last - range.first + 1)
                dst.flush()
                if (idx + 1 < slices.size) {
                    try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                }
            }
        }

        /**
         * Verifies a candidate proxy hop by performing a real SOCKS5/HTTP CONNECT tunnel handshake
         * and fetching `/cdn-cgi/trace` through it to measure real relay RTT and exit IP/country/WARP status.
         */
        fun probeHopTrace(
            hop: TunnelConfig.ProxyHop,
            timeoutMs: Int = 1800,
            traceHost: String = "1.1.1.1",
            tracePort: Int = 443,
            useTls: Boolean = true
        ): HopTraceResult? {
            if (hop.scheme == TunnelConfig.ProxyScheme.DIRECT || hop.host.isBlank() || hop.port !in 1..65535) {
                return null
            }
            val start = System.nanoTime()
            val bridge = LocalProxyServer(
                requestedPort = 0,
                upstream = TunnelConfig.toLocalProxyUpstream(listOf(hop)),
                connectTimeoutMs = timeoutMs,
                socketReadTimeoutMs = timeoutMs
            )
            return try {
                val localPort = bridge.start()
                val rawSock = Socket()
                try {
                    rawSock.soTimeout = timeoutMs
                    rawSock.tcpNoDelay = true
                    rawSock.connect(InetSocketAddress("127.0.0.1", localPort), timeoutMs)
                    val out = rawSock.getOutputStream()
                    val ins = rawSock.getInputStream()
                    out.write(
                        "CONNECT $traceHost:$tracePort HTTP/1.1\r\nHost: $traceHost:$tracePort\r\n\r\n"
                            .toByteArray(StandardCharsets.US_ASCII)
                    )
                    out.flush()
                    val status = readStatusLineBounded(ins) ?: return null
                    val code = status.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
                    if (code !in 200..299) return null
                    // Drain remaining HTTP response headers from CONNECT
                    drainBlankLine(ins)

                    val streamSock: Socket = if (useTls) {
                        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(rawSock, traceHost, tracePort, true) as SSLSocket
                        tls.soTimeout = timeoutMs
                        tls.startHandshake()
                        tls
                    } else {
                        rawSock
                    }
                    try {
                        val sOut = streamSock.getOutputStream()
                        val sIn = streamSock.getInputStream()
                        sOut.write(
                            "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $traceHost\r\nConnection: close\r\nUser-Agent: DastYab/1.9\r\n\r\n"
                                .toByteArray(StandardCharsets.US_ASCII)
                        )
                        sOut.flush()
                        val rawResp = readTextBounded(sIn, 8192)
                        val body = rawResp.substringAfter("\r\n\r\n", rawResp.substringAfter("\n\n", ""))
                        if (body.isBlank()) return null
                        val trace = NetworkTools.parseCloudflareTrace(body)
                        val ip = trace.ip.takeIf { it.isNotBlank() && it != "unknown" }.orEmpty()
                        val cc = trace.location.uppercase().takeIf { it.length == 2 && it != "XX" }.orEmpty()
                        if (ip.isEmpty()) return null
                        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                        HopTraceResult(hop, ms, ip, cc, trace.warp)
                    } finally {
                        if (streamSock !== rawSock) runCatching { streamSock.close() }
                    }
                } finally {
                    runCatching { rawSock.close() }
                }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { bridge.close() }
            }
        }

        private fun readStatusLineBounded(ins: InputStream): String? {
            val buf = ByteArrayOutputStream(64)
            var count = 0
            while (count < 512) {
                val b = ins.read()
                if (b < 0) return null
                count++
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString(StandardCharsets.US_ASCII.name())
        }

        private fun drainBlankLine(ins: InputStream) {
            val line = ByteArrayOutputStream(64)
            var guard = 0
            while (guard < 4096) {
                val b = ins.read()
                if (b < 0) break
                guard++
                if (b == '\n'.code) {
                    if (line.size() == 0) break
                    line.reset()
                } else if (b != '\r'.code) {
                    line.write(b)
                }
            }
        }

        private fun readTextBounded(ins: InputStream, maxBytes: Int): String {
            val out = ByteArrayOutputStream(maxBytes.coerceAtMost(2048))
            val buf = ByteArray(1024)
            var total = 0
            while (total < maxBytes) {
                val n = try { ins.read(buf, 0, (maxBytes - total).coerceAtMost(buf.size)) } catch (_: Exception) { -1 }
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        }

        fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
            val trimmed = authority.trim()
            if (trimmed.isEmpty()) return null
            return if (trimmed.startsWith("[")) {
                val close = trimmed.indexOf(']')
                if (close <= 1) return null
                val host = trimmed.substring(1, close)
                val portPart = trimmed.substring(close + 1).removePrefix(":")
                val port = if (portPart.isEmpty()) defaultPort else portPart.toIntOrNull() ?: return null
                if (port !in 1..65535) null else host to port
            } else {
                val idx = trimmed.lastIndexOf(':')
                if (idx <= 0) {
                    trimmed to defaultPort
                } else {
                    val host = trimmed.substring(0, idx)
                    val port = trimmed.substring(idx + 1).toIntOrNull() ?: return null
                    if (host.isEmpty() || port !in 1..65535) null else host to port
                }
            }
        }
    }
}
