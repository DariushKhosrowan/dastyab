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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    private val socketReadTimeoutMs: Int = 120_000
) : Closeable {

    sealed class Upstream {
        object Direct : Upstream() {
            override fun describe(): String = "Direct / Active System VPN"
        }

        data class Http(val host: String, val port: Int) : Upstream() {
            override fun describe(): String = "HTTP $host:$port"
        }

        data class Socks5(val host: String, val port: Int) : Upstream() {
            override fun describe(): String = "SOCKS5 $host:$port"
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
        return when (upstream) {
            is Upstream.Direct -> {
                Socket().apply {
                    tcpNoDelay = true
                    soTimeout = socketReadTimeoutMs
                    connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
                }
            }
            is Upstream.Http -> {
                val sock = Socket().apply {
                    tcpNoDelay = true
                    soTimeout = connectTimeoutMs
                    connect(InetSocketAddress(upstream.host, upstream.port), connectTimeoutMs)
                }
                try {
                    val req = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
                    sock.getOutputStream().write(req.toByteArray(StandardCharsets.US_ASCII))
                    sock.getOutputStream().flush()
                    val respLines = readHttpHeaders(sock.getInputStream())
                        ?: throw IOException("Empty response from upstream HTTP proxy")
                    val statusLine = respLines.firstOrNull().orEmpty()
                    val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                    if (code !in 200..299) {
                        throw IOException("Upstream HTTP proxy rejected CONNECT: $statusLine")
                    }
                    sock.soTimeout = socketReadTimeoutMs
                    sock
                } catch (e: IOException) {
                    runCatching { sock.close() }
                    throw e
                }
            }
            is Upstream.Socks5 -> {
                val sock = Socket().apply {
                    tcpNoDelay = true
                    soTimeout = connectTimeoutMs
                    connect(InetSocketAddress(upstream.host, upstream.port), connectTimeoutMs)
                }
                try {
                    val out = sock.getOutputStream()
                    val ins = sock.getInputStream()
                    // Greeting: VER=5, NMETHODS=1, NOAUTH=0
                    out.write(byteArrayOf(0x05, 0x01, 0x00))
                    out.flush()
                    val authReply = readExact(ins, 2)
                        ?: throw IOException("Upstream SOCKS5 EOF during greeting")
                    if (authReply[0] != 0x05.toByte() || authReply[1] != 0x00.toByte()) {
                        throw IOException("Upstream SOCKS5 auth rejected (${authReply[1].toInt() and 0xFF})")
                    }
                    // Domain CONNECT request
                    val hostBytes = targetHost.toByteArray(StandardCharsets.US_ASCII)
                    if (hostBytes.size > 255) throw IOException("Target host too long for SOCKS5")
                    val req = ByteArray(7 + hostBytes.size)
                    req[0] = 0x05
                    req[1] = 0x01 // CONNECT
                    req[2] = 0x00 // RSV
                    req[3] = 0x03 // DOMAINNAME
                    req[4] = hostBytes.size.toByte()
                    System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
                    req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
                    req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
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
                            if (dLen > 0) readExact(ins, dLen + 2) else readExact(ins, 2)
                        }
                        else -> throw IOException("Upstream SOCKS5 unknown address type")
                    } ?: throw IOException("Upstream SOCKS5 truncated bind address")

                    sock.soTimeout = socketReadTimeoutMs
                    sock
                } catch (e: IOException) {
                    runCatching { sock.close() }
                    throw e
                }
            }
        }
    }

    private fun relayBidirectional(clientIn: InputStream, clientOut: OutputStream, remote: Socket) {
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()
        val up = thread(isDaemon = true, name = "dastyab-pipe-up") {
            copyStream(clientIn, remoteOut)
            runCatching { remote.shutdownOutput() }
        }
        copyStream(remoteIn, clientOut)
        runCatching { clientOut.flush() }
        runCatching { up.join(1500) }
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

    companion object {
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
