package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Hermetic JVM unit & loopback socket tests for [GeoBypassEngine] and [LocalProxyServer].
 */
class GeoBypassAndProxyTest {

    @Test
    fun flowSupportedCountries_matchesOfficialAllowlist() {
        assertEquals(151, GeoBypassEngine.FLOW_SUPPORTED_COUNTRIES.size)
        for (cc in listOf("US", "de", "GB", "jp", "SG", "AE", "TR", "IN", "BR", "CA")) {
            assertTrue("Expected $cc to be supported", GeoBypassEngine.isFlowCountrySupported(cc))
        }
        for (blocked in listOf("IR", "ir", "CN", "RU", "KP", "BY", "SY", "CU", "XX", "", "  ")) {
            assertFalse("Expected $blocked to be rejected", GeoBypassEngine.isFlowCountrySupported(blocked))
        }
    }

    @Test
    fun isFlowUnsupportedResponse_detectsRedirectAndHtmlMarkers() {
        assertTrue(GeoBypassEngine.isFlowUnsupportedResponse(302, "https://flow.google.com/unsupported-country", null))
        assertTrue(GeoBypassEngine.isFlowUnsupportedResponse(200, "", "<h1 class=\"unsupported-country-title\">Sorry</h1>"))
        assertTrue(GeoBypassEngine.isFlowUnsupportedResponse(200, "", "Flow is not available in your country yet."))
        assertTrue(GeoBypassEngine.isFlowUnsupportedResponse(451, "", ""))
        assertFalse(GeoBypassEngine.isFlowUnsupportedResponse(302, "https://accounts.google.com/v3/signin", ""))
        assertFalse(GeoBypassEngine.isFlowUnsupportedResponse(200, "", "<title>Flow</title>"))
    }

    @Test
    fun planRoute_autoSmartNeverStealsExternalVpn() {
        val settings = TunnelConfig.Settings(
            googleAccessMode = TunnelConfig.GoogleAccessMode.AUTO_SMART,
            autoVpnForGoogle = true
        )
        val plan = GeoBypassEngine.planRoute(
            settings = settings,
            externalVpnActive = true,
            dastyabVpnActive = false,
            runningBridgePort = null,
            detectedListeningHttpPort = null
        )
        assertFalse("Must not start DastYab VpnService when Lantern/external VPN is active", plan.shouldStartDastyabVpn)
        assertEquals(Proxy.NO_PROXY, plan.javaNetProxy)
        assertTrue(plan.externalVpnActive)
    }

    @Test
    fun planRoute_inAppProxyWithSocks5StartsHttpBridgeWithoutVpn() {
        val settings = TunnelConfig.Settings(
            googleAccessMode = TunnelConfig.GoogleAccessMode.IN_APP_PROXY,
            upstreamProxyScheme = TunnelConfig.ProxyScheme.SOCKS5,
            upstreamProxyHost = "127.0.0.1",
            upstreamProxyPort = 10808,
            localBridgePort = 10850
        )
        val plan = GeoBypassEngine.planRoute(
            settings = settings,
            externalVpnActive = true,
            dastyabVpnActive = false,
            runningBridgePort = null,
            detectedListeningHttpPort = null
        )
        assertFalse(plan.shouldStartDastyabVpn)
        assertTrue("SOCKS5 upstream requires local HTTP->SOCKS5 bridge for WebView", plan.shouldStartLocalBridge)
        assertEquals(10850, plan.webViewHttpProxyPort)
        assertEquals(Proxy.Type.SOCKS, plan.javaNetProxy.type())
    }

    @Test
    fun formatV2rayNgSocksUri_buildsValidShareLink() {
        assertEquals(
            "socks://127.0.0.1:10850#DastYab-Local-Bridge",
            GeoBypassEngine.formatV2rayNgSocksUri(" 127.0.0.1 ", 10850)
        )
    }

    @Test
    fun localProxyServer_handlesHttpConnectAndSocks5AndMultiHopChainingOnLoopback() {
        // Start a minimal loopback TCP echo server
        val echoServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val echoThread = thread(isDaemon = true) {
            while (!echoServer.isClosed) {
                val s = try { echoServer.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    s.use { sock ->
                        val ins = sock.getInputStream()
                        val out = sock.getOutputStream()
                        val buf = ByteArray(1024)
                        while (true) {
                            val n = try { ins.read(buf) } catch (_: Exception) { -1 }
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            out.flush()
                        }
                    }
                }
            }
        }

        // Upstream proxy A (accepts SOCKS5 + HTTP and connects Direct to Echo)
        val upstreamProxy = LocalProxyServer(requestedPort = 0, upstream = LocalProxyServer.Upstream.Direct)
        val upstreamPort = upstreamProxy.start()

        // Layered Bridge proxy B (accepts HTTP + SOCKS5 and chains through Upstream SOCKS5)
        val bridgeProxy = LocalProxyServer(
            requestedPort = 0,
            upstream = LocalProxyServer.Upstream.Socks5("127.0.0.1", upstreamPort)
        )
        val bridgePort = bridgeProxy.start()

        try {
            // 1. Direct HTTP CONNECT -> Upstream Proxy A -> Echo
            val httpEcho = sendViaHttpConnect("127.0.0.1", upstreamPort, "127.0.0.1", echoServer.localPort, "ping-http")
            assertEquals("ping-http", httpEcho)

            // 2. Direct SOCKS5 -> Upstream Proxy A (same port!) -> Echo
            val socksEcho = sendViaSocks5("127.0.0.1", upstreamPort, "127.0.0.1", echoServer.localPort, "ping-socks5")
            assertEquals("ping-socks5", socksEcho)

            // 3. Layered Multi-Hop: HTTP CONNECT -> Bridge B -> SOCKS5 Upstream A -> Echo
            val layeredEcho = sendViaHttpConnect("127.0.0.1", bridgePort, "127.0.0.1", echoServer.localPort, "ping-multihop")
            assertEquals("ping-multihop", layeredEcho)
        } finally {
            bridgeProxy.close()
            upstreamProxy.close()
            echoServer.close()
            echoThread.interrupt()
        }
    }

    private fun sendViaHttpConnect(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        payload: String
    ): String {
        Socket().use { sock ->
            sock.soTimeout = 4000
            sock.connect(InetSocketAddress(proxyHost, proxyPort), 3000)
            val out = sock.getOutputStream()
            val ins = sock.getInputStream()
            out.write("CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            // Read HTTP 200 response header ending in \r\n\r\n
            val hdr = ByteArrayOutputStream()
            val window = ByteArray(4)
            var matched = 0
            while (true) {
                val b = ins.read()
                if (b < 0) break
                hdr.write(b)
                window[0] = window[1]; window[1] = window[2]; window[2] = window[3]; window[3] = b.toByte()
                if (window[0] == '\r'.code.toByte() && window[1] == '\n'.code.toByte() &&
                    window[2] == '\r'.code.toByte() && window[3] == '\n'.code.toByte()
                ) {
                    matched = 1
                    break
                }
            }
            assertEquals(1, matched)
            assertTrue(String(hdr.toByteArray(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200"))

            val reqBytes = payload.toByteArray(StandardCharsets.UTF_8)
            out.write(reqBytes)
            out.flush()

            val reply = ByteArray(reqBytes.size)
            var off = 0
            while (off < reply.size) {
                val n = ins.read(reply, off, reply.size - off)
                if (n < 0) break
                off += n
            }
            return String(reply, 0, off, StandardCharsets.UTF_8)
        }
    }

    private fun sendViaSocks5(
        proxyHost: String,
        proxyPort: Int,
        targetHost: String,
        targetPort: Int,
        payload: String
    ): String {
        Socket().use { sock ->
            sock.soTimeout = 4000
            sock.connect(InetSocketAddress(proxyHost, proxyPort), 3000)
            val out = sock.getOutputStream()
            val ins = sock.getInputStream()

            // Greeting
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greetReply = ByteArray(2)
            assertEquals(2, ins.read(greetReply))
            assertEquals(0x05.toByte(), greetReply[0])
            assertEquals(0x00.toByte(), greetReply[1])

            // Domain CONNECT
            val hostBytes = targetHost.toByteArray(StandardCharsets.US_ASCII)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03; req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
            req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            out.write(req)
            out.flush()

            val rep = ByteArray(10)
            var repRead = 0
            while (repRead < 10) {
                val n = ins.read(rep, repRead, 10 - repRead)
                if (n < 0) break
                repRead += n
            }
            assertEquals(10, repRead)
            assertEquals(0x00.toByte(), rep[1])

            val reqBytes = payload.toByteArray(StandardCharsets.UTF_8)
            out.write(reqBytes)
            out.flush()

            val reply = ByteArray(reqBytes.size)
            var off = 0
            while (off < reply.size) {
                val n = ins.read(reply, off, reply.size - off)
                if (n < 0) break
                off += n
            }
            return String(reply, 0, off, StandardCharsets.UTF_8)
        }
    }

    private fun relayTestSockets(client: Socket, upstream: Socket) {
        client.soTimeout = 5000
        upstream.soTimeout = 5000
        client.tcpNoDelay = true
        upstream.tcpNoDelay = true
        val ci = client.getInputStream()
        val co = client.getOutputStream()
        val ui = upstream.getInputStream()
        val uo = upstream.getOutputStream()
        fun pump(src: java.io.InputStream, dst: java.io.OutputStream) {
            val buf = ByteArray(4096)
            while (true) {
                val n = try { src.read(buf) } catch (_: Exception) { -1 }
                if (n <= 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        }
        val t = thread(isDaemon = true) {
            runCatching {
                pump(ci, uo)
                upstream.shutdownOutput()
            }
        }
        runCatching {
            pump(ui, co)
            co.flush()
        }
        t.join(2000)
    }

    private fun startLoopbackEchoServer(): Pair<ServerSocket, Thread> {
        val echoServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val echoThread = thread(isDaemon = true) {
            while (!echoServer.isClosed) {
                val s = try { echoServer.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    s.use { sock ->
                        sock.soTimeout = 5000
                        sock.tcpNoDelay = true
                        val ins = sock.getInputStream()
                        val out = sock.getOutputStream()
                        val buf = ByteArray(1024)
                        while (true) {
                            val n = try { ins.read(buf) } catch (_: Exception) { -1 }
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            out.flush()
                        }
                    }
                }
            }
        }
        return echoServer to echoThread
    }

    private fun startLoopbackHttpAuthProxy(expectedUser: String, expectedPass: String): Pair<ServerSocket, Thread> {
        val expectedToken = java.util.Base64.getEncoder()
            .encodeToString("$expectedUser:$expectedPass".toByteArray(StandardCharsets.UTF_8))
        val srv = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) {
            while (!srv.isClosed) {
                val c = try { srv.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    c.use { sock ->
                        sock.soTimeout = 5000
                        sock.tcpNoDelay = true
                        val ins = sock.getInputStream()
                        val out = sock.getOutputStream()
                        val buf = ByteArrayOutputStream()
                        val win = ByteArray(4)
                        while (true) {
                            val b = ins.read()
                            if (b < 0) return@use
                            buf.write(b)
                            win[0] = win[1]; win[1] = win[2]; win[2] = win[3]; win[3] = b.toByte()
                            if (win.contentEquals("\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))) break
                        }
                        val hdrText = String(buf.toByteArray(), StandardCharsets.US_ASCII)
                        val authOk = hdrText.lines().any {
                            it.startsWith("Proxy-Authorization: Basic ", ignoreCase = true) &&
                                it.contains(expectedToken)
                        }
                        if (!authOk) {
                            out.write("HTTP/1.1 407 Proxy Auth Required\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            out.flush()
                            return@use
                        }
                        val targetAuth = hdrText.lines().first().split(' ')[1]
                        val (tHost, tPort) = LocalProxyServer.parseHostPort(targetAuth, 443)!!
                        Socket().use { up ->
                            up.connect(InetSocketAddress(tHost, tPort), 3000)
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            out.flush()
                            relayTestSockets(sock, up)
                        }
                    }
                }
            }
        }
        return srv to t
    }

    private fun startLoopbackSocks5AuthProxy(expectedUser: String, expectedPass: String): Pair<ServerSocket, Thread> {
        val srv = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) {
            while (!srv.isClosed) {
                val c = try { srv.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    c.use { sock ->
                        sock.soTimeout = 5000
                        sock.tcpNoDelay = true
                        val ins = DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        val ver = ins.readUnsignedByte()
                        val nm = ins.readUnsignedByte()
                        if (ver != 5 || nm <= 0) return@use
                        val methods = ByteArray(nm); ins.readFully(methods)
                        if (!methods.contains(0x02.toByte())) {
                            out.write(byteArrayOf(0x05, 0xFF.toByte()))
                            out.flush()
                            return@use
                        }
                        out.write(byteArrayOf(0x05, 0x02)); out.flush()
                        val aver = ins.readUnsignedByte()
                        val ulen = ins.readUnsignedByte(); val ub = ByteArray(ulen); ins.readFully(ub)
                        val plen = ins.readUnsignedByte(); val pb = ByteArray(plen); ins.readFully(pb)
                        if (aver != 1 || String(ub, StandardCharsets.UTF_8) != expectedUser || String(pb, StandardCharsets.UTF_8) != expectedPass) {
                            out.write(byteArrayOf(0x01, 0x01)); out.flush(); return@use
                        }
                        out.write(byteArrayOf(0x01, 0x00)); out.flush()
                        val hdr = ByteArray(4); ins.readFully(hdr)
                        val atyp = hdr[3].toInt() and 0xFF
                        val host = when (atyp) {
                            3 -> {
                                val hl = ins.readUnsignedByte(); val hb = ByteArray(hl); ins.readFully(hb)
                                String(hb, StandardCharsets.US_ASCII)
                            }
                            1 -> {
                                val ab = ByteArray(4); ins.readFully(ab)
                                InetAddress.getByAddress(ab).hostAddress
                            }
                            else -> {
                                out.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                                return@use
                            }
                        }
                        val portB = ByteArray(2); ins.readFully(portB)
                        val tPort = ((portB[0].toInt() and 0xFF) shl 8) or (portB[1].toInt() and 0xFF)
                        Socket().use { up ->
                            up.connect(InetSocketAddress(host, tPort), 3000)
                            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                            relayTestSockets(sock, up)
                        }
                    }
                }
            }
        }
        return srv to t
    }

    @Test
    fun localProxyServer_chainsThroughHttpBasicAuthProxyOnLoopback() {
        val (echoServer, echoThread) = startLoopbackEchoServer()
        val (httpAuthServer, httpAuthThread) = startLoopbackHttpAuthProxy("u1", "p1")
        val proxy = LocalProxyServer(
            requestedPort = 0,
            upstream = LocalProxyServer.Upstream.Http("127.0.0.1", httpAuthServer.localPort, "u1", "p1")
        )
        val port = proxy.start()
        try {
            val echoed = sendViaHttpConnect("127.0.0.1", port, "127.0.0.1", echoServer.localPort, "http-auth-ok")
            assertEquals("http-auth hop failed (err=${proxy.stats().lastError})", "http-auth-ok", echoed)
        } finally {
            proxy.close()
            httpAuthServer.close()
            echoServer.close()
            httpAuthThread.interrupt()
            echoThread.interrupt()
        }
    }

    @Test
    fun localProxyServer_chainsThroughSocks5UserPassAuthProxyOnLoopback() {
        val (echoServer, echoThread) = startLoopbackEchoServer()
        val (socksAuthServer, socksAuthThread) = startLoopbackSocks5AuthProxy("u2", "p2")
        val proxy = LocalProxyServer(
            requestedPort = 0,
            upstream = LocalProxyServer.Upstream.Socks5("127.0.0.1", socksAuthServer.localPort, "u2", "p2")
        )
        val port = proxy.start()
        try {
            val echoed = sendViaSocks5("127.0.0.1", port, "127.0.0.1", echoServer.localPort, "socks5-auth-ok")
            assertEquals("socks5-auth hop failed (err=${proxy.stats().lastError})", "socks5-auth-ok", echoed)
        } finally {
            proxy.close()
            socksAuthServer.close()
            echoServer.close()
            socksAuthThread.interrupt()
            echoThread.interrupt()
        }
    }

    @Test
    fun localProxyServer_threeHopChainWithHttpAndSocks5AuthOnLoopback() {
        val (echoServer, echoThread) = startLoopbackEchoServer()

        // Hop 3 (Exit): LocalProxyServer Direct
        val exitProxy = LocalProxyServer(requestedPort = 0, upstream = LocalProxyServer.Upstream.Direct)
        val exitPort = exitProxy.start()

        // Hop 2 (Middle): RFC 1929 SOCKS5 proxy ("u2":"p2") that connects directly to the requested next hop (exitPort)
        val (socksAuthServer, socksAuthThread) = startLoopbackSocks5AuthProxy("u2", "p2")

        // Hop 1 (Entry): HTTP CONNECT Basic-auth proxy ("u1":"p1") that connects directly to the requested next hop (socksAuthServer)
        val (httpAuthServer, httpAuthThread) = startLoopbackHttpAuthProxy("u1", "p1")

        val entryChainProxy = LocalProxyServer(
            requestedPort = 0,
            upstream = LocalProxyServer.Upstream.Chain(
                listOf(
                    LocalProxyServer.Upstream.Http("127.0.0.1", httpAuthServer.localPort, "u1", "p1"),
                    LocalProxyServer.Upstream.Socks5("127.0.0.1", socksAuthServer.localPort, "u2", "p2"),
                    LocalProxyServer.Upstream.Socks5("127.0.0.1", exitPort)
                )
            )
        )
        val entryPort = entryChainProxy.start()

        try {
            val echoed = sendViaHttpConnect("127.0.0.1", entryPort, "127.0.0.1", echoServer.localPort, "three-hop-ok")
            assertEquals(
                "three-hop proxy did not relay the payload (entryErr=${entryChainProxy.stats().lastError}, exitErr=${exitProxy.stats().lastError})",
                "three-hop-ok",
                echoed
            )
        } finally {
            entryChainProxy.close()
            httpAuthServer.close()
            socksAuthServer.close()
            exitProxy.close()
            echoServer.close()
            httpAuthThread.interrupt()
            socksAuthThread.interrupt()
            echoThread.interrupt()
        }
    }

    @Test
    fun accessProbeReport_separatesAiStudioAndFlowReadiness() {
        val okAi = GeoBypassEngine.ServiceProbeResult(true, 200, 120, false, "https://aistudio.google.com/", "ok", appShellOk = true)
        val okFlow = GeoBypassEngine.ServiceProbeResult(true, 200, 140, false, "https://flow.google.com/", "ok", appShellOk = true)

        // 1. Egress null must fail BOTH services even if HTTP responses look reachable
        val noEgress = GeoBypassEngine.AccessProbeReport(
            routeLabel = "Lantern",
            egress = null,
            flowCountrySupported = false,
            aiStudio = okAi,
            flow = okFlow,
            headline = "Unknown",
            recommendation = "",
            accountsReachable = true
        )
        assertFalse(noEgress.readyForAiStudio)
        assertFalse(noEgress.readyForFlow)

        // 2. Embargoed country (IR) must fail both even if cached/answered 200
        val irEgress = GeoBypassEngine.EgressInfo("5.5.5.5", "IR", "Iran", false, "ipwho.is")
        val irReport = GeoBypassEngine.AccessProbeReport(
            routeLabel = "Direct",
            egress = irEgress,
            flowCountrySupported = false,
            aiStudio = okAi,
            flow = okFlow,
            headline = "Blocked",
            recommendation = "",
            accountsReachable = true
        )
        assertFalse(irReport.readyForAiStudio)
        assertFalse(irReport.readyForFlow)

        // 3. Country supported by AI Studio (LY) or Flow missing app shell: AI Studio ready, Flow NOT ready
        val lyEgress = GeoBypassEngine.EgressInfo("41.208.1.2", "LY", "Libya", false, "ipwho.is")
        val missingShellFlow = GeoBypassEngine.ServiceProbeResult(true, 200, 150, false, "https://flow.google.com/", "no shell", appShellOk = false)
        val splitReport = GeoBypassEngine.AccessProbeReport(
            routeLabel = "Lantern",
            egress = lyEgress,
            flowCountrySupported = false,
            aiStudio = okAi,
            flow = missingShellFlow,
            headline = "Split",
            recommendation = "",
            accountsReachable = true
        )
        assertTrue(splitReport.readyForAiStudio)
        assertFalse(splitReport.readyForFlow)
        assertTrue(splitReport.targetReady(GeoBypassEngine.TargetService.AI_STUDIO))
        assertFalse(splitReport.targetReady(GeoBypassEngine.TargetService.GOOGLE_FLOW))

        // 4. Google Sign-in blocked fails both
        val deEgress = GeoBypassEngine.EgressInfo("185.1.1.1", "DE", "Germany", false, "ipwho.is")
        val noAccounts = GeoBypassEngine.AccessProbeReport(
            routeLabel = "Chain",
            egress = deEgress,
            flowCountrySupported = true,
            aiStudio = okAi,
            flow = okFlow,
            headline = "No accounts",
            recommendation = "",
            accountsReachable = false
        )
        assertFalse(noAccounts.readyForAiStudio)
        assertFalse(noAccounts.readyForFlow)

        val cleanFlowReport = noAccounts.copy(accountsReachable = true)
        assertTrue(cleanFlowReport.readyForFlow)
        assertTrue(
            GeoBypassEngine.gateScore(cleanFlowReport, GeoBypassEngine.TargetService.GOOGLE_FLOW) <
                GeoBypassEngine.gateScore(splitReport, GeoBypassEngine.TargetService.GOOGLE_FLOW)
        )
    }

    @Test
    fun probeGoogleEndpoint_detectsGeoRedirectsMarkersAndFlowShell() {
        val srv = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) {
            while (!srv.isClosed) {
                val c = try { srv.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    c.use { sock ->
                        val ins = sock.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                        val reqLine = ins.readLine() ?: return@use
                        while (true) {
                            val l = ins.readLine() ?: break
                            if (l.isEmpty()) break
                        }
                        val path = reqLine.split(' ').getOrNull(1).orEmpty()
                        val resp = when (path) {
                            "/flow-redir" ->
                                "HTTP/1.1 302 Found\r\nLocation: /unsupported-country\r\nContent-Length: 0\r\n\r\n"
                            "/flow-ok" -> {
                                val body = "<html><head><title>Flow</title></head><body><app-root ng-version=\"18\"><script>AiSandboxAngularFrontend</script></app-root></body></html>"
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
                            }
                            "/flow-noshell" -> {
                                val body = "<html><body>Sign in required</body></html>"
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
                            }
                            "/ai-geoblock" -> {
                                val body = "<html><body>Sorry, Google AI Studio isn't available in your country.</body></html>"
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
                            }
                            "/ai-redir" ->
                                "HTTP/1.1 302 Found\r\nLocation: https://ai.google.dev/available-regions\r\nContent-Length: 0\r\n\r\n"
                            else -> "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"
                        }
                        sock.getOutputStream().write(resp.toByteArray(StandardCharsets.UTF_8))
                        sock.getOutputStream().flush()
                    }
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${srv.localPort}"
            val flowRedir = GeoBypassEngine.probeGoogleEndpoint("$base/flow-redir", Proxy.NO_PROXY, 2000, inspectFlowGate = true)
            assertTrue(flowRedir.unsupportedRegion)
            assertFalse(flowRedir.reachable)

            val flowOk = GeoBypassEngine.probeGoogleEndpoint("$base/flow-ok", Proxy.NO_PROXY, 2000, inspectFlowGate = true)
            assertTrue(flowOk.reachable)
            assertTrue(flowOk.appShellOk)
            assertFalse(flowOk.unsupportedRegion)

            val flowNoShell = GeoBypassEngine.probeGoogleEndpoint("$base/flow-noshell", Proxy.NO_PROXY, 2000, inspectFlowGate = true)
            assertFalse(flowNoShell.appShellOk)

            val aiGeo = GeoBypassEngine.probeGoogleEndpoint("$base/ai-geoblock", Proxy.NO_PROXY, 2000, inspectAiStudioGate = true)
            assertTrue(aiGeo.unsupportedRegion)
            assertFalse(aiGeo.reachable)

            val aiRedir = GeoBypassEngine.probeGoogleEndpoint("$base/ai-redir", Proxy.NO_PROXY, 2000, inspectAiStudioGate = true)
            assertTrue(aiRedir.unsupportedRegion)
            assertFalse(aiRedir.reachable)
        } finally {
            srv.close()
            t.interrupt()
        }
    }

    @Test
    fun planRoute_separatesAiStudioAndFlowChainsOnTopOfExternalVpn() {
        val settings = TunnelConfig.Settings(
            googleAccessMode = TunnelConfig.GoogleAccessMode.AUTO_SMART,
            localBridgePort = 10850,
            aiStudioProxyChain = "http://127.0.0.1:10809",
            flowProxyChain = "socks5://127.0.0.1:10808, http://10.0.0.2:8080"
        )
        val aiPlan = GeoBypassEngine.planRoute(
            settings = settings,
            externalVpnActive = true,
            dastyabVpnActive = false,
            runningBridgePort = null,
            detectedListeningHttpPort = null,
            targetService = GeoBypassEngine.TargetService.AI_STUDIO
        )
        val flowPlan = GeoBypassEngine.planRoute(
            settings = settings,
            externalVpnActive = true,
            dastyabVpnActive = false,
            runningBridgePort = null,
            detectedListeningHttpPort = null,
            targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW
        )
        assertFalse(aiPlan.shouldStartDastyabVpn)
        assertFalse(flowPlan.shouldStartDastyabVpn)
        assertTrue(aiPlan.externalVpnActive)
        assertTrue(flowPlan.externalVpnActive)
        assertEquals(10809, aiPlan.webViewHttpProxyPort)
        assertEquals(10850, flowPlan.webViewHttpProxyPort)
        assertTrue(flowPlan.shouldStartLocalBridge)
        assertTrue(flowPlan.routeLabel.contains("2-Hop Chain"))
    }

    @Test
    fun buildMultiLayerCandidateChains_generatesCascadesWithoutSelfOrDuplicateHops() {
        val listening = listOf(
            GeoBypassEngine.ListeningProxy(GeoBypassEngine.KNOWN_LOCAL_PROXY_PRESETS[0], 5),
            GeoBypassEngine.ListeningProxy(GeoBypassEngine.KNOWN_LOCAL_PROXY_PRESETS[1], 8)
        )
        val harvested = listOf(
            TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "198.51.100.1", 1080),
            TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.HTTP, "198.51.100.2", 8080),
            TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "198.51.100.3", 1080)
        )
        val chains = GeoBypassEngine.buildMultiLayerCandidateChains(
            existingHops = emptyList(),
            listening = listening,
            harvestedHops = harvested,
            maxCandidates = 24
        )
        assertTrue(chains.first().isEmpty()) // Layer 0 (External VPN / Direct)
        assertTrue(chains.any { it.size == 1 })
        assertTrue(chains.any { it.size == 2 })
        assertTrue(chains.any { it.size == 3 })
        for (c in chains) {
            assertEquals(c.size, c.distinctBy { "${it.host}:${it.port}" }.size)
        }
    }

    @Test
    fun buildProgressiveLayerStages_escalates0To4AndTestsBothHopOrders() {
        val listening = listOf(
            GeoBypassEngine.ListeningProxy(GeoBypassEngine.KNOWN_LOCAL_PROXY_PRESETS[0], 4)
        )
        val a = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "198.51.100.10", 1080)
        val b = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.HTTP, "198.51.100.20", 8080)
        val c = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "198.51.100.30", 1080)
        val d = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.HTTP, "198.51.100.40", 3128)

        val stages = GeoBypassEngine.buildProgressiveLayerStages(
            existingHops = emptyList(),
            listening = listening,
            harvestedHops = listOf(a, b, c, d),
            preferredEntries = listOf(a),
            preferredExits = listOf(c),
            maxTotalCandidates = 90
        )

        assertEquals(5, stages.size)
        assertTrue(stages[0].all { it.isEmpty() })
        assertTrue(stages[1].isNotEmpty() && stages[1].all { it.size == 1 })
        assertTrue(stages[2].isNotEmpty() && stages[2].all { it.size == 2 })
        assertTrue(stages[3].isNotEmpty() && stages[3].all { it.size == 3 })
        assertTrue(stages[4].isNotEmpty() && stages[4].all { it.size == 4 })

        // Verify both [A, C] and [C, A] orders are generated in Stage 2
        val stage2Keys = stages[2].map { chain -> chain.joinToString("->") { "${it.host}:${it.port}" } }
        assertTrue(stage2Keys.contains("198.51.100.10:1080->198.51.100.30:1080"))
        assertTrue(stage2Keys.contains("198.51.100.30:1080->198.51.100.10:1080"))
        // Preferred exit C is prioritized up front among harvested Stage-1 candidates
        assertEquals("198.51.100.30", stages[1][1].first().host)
    }

    @Test
    fun isAiStudioCountrySupported_rejectsEmbargoedAndUnknownTerritories() {
        for (ok in listOf("US", "de", "LY", "GB", "JP", "SG", "TR")) {
            assertTrue("Expected $ok to be supported by AI Studio", GeoBypassEngine.isAiStudioCountrySupported(ok))
        }
        for (blocked in listOf("IR", "ir", "CN", "RU", "KP", "BY", "SY", "CU", "SD", "AF", "HK", "MO", "MM", "SS", "XX", "", " ")) {
            assertFalse("Expected $blocked to be blocked for AI Studio", GeoBypassEngine.isAiStudioCountrySupported(blocked))
        }
    }

    @Test
    fun isEligibleRouteReport_enforcesTargetReadyAndExcludedIps() {
        val okAi = GeoBypassEngine.ServiceProbeResult(true, 200, 90, false, "https://aistudio.google.com/", "ok", appShellOk = true)
        val okFlow = GeoBypassEngine.ServiceProbeResult(true, 200, 110, false, "https://flow.google.com/", "ok", appShellOk = true)
        val usEgress = GeoBypassEngine.EgressInfo("203.0.113.10", "US", "United States", false, "ipwho.is")
        val report = GeoBypassEngine.AccessProbeReport(
            routeLabel = "Chain",
            egress = usEgress,
            flowCountrySupported = true,
            aiStudio = okAi,
            flow = okFlow,
            headline = "Ready",
            recommendation = "",
            accountsReachable = true
        )
        assertTrue(GeoBypassEngine.isEligibleRouteReport(report, GeoBypassEngine.TargetService.GOOGLE_FLOW))
        assertFalse(
            "Must reject blocked current IP",
            GeoBypassEngine.isEligibleRouteReport(report, GeoBypassEngine.TargetService.GOOGLE_FLOW, setOf("203.0.113.10"))
        )
        assertFalse(
            "Must reject null egress",
            GeoBypassEngine.isEligibleRouteReport(report.copy(egress = null), GeoBypassEngine.TargetService.AI_STUDIO)
        )
    }

    @Test
    fun classifyQualifiedTraces_separatesCleanExitsAndPreservesSubnetDiversity() {
        val irHop = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "10.10.1.1", 1080)
        val excludedUsHop = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "10.20.1.1", 1080)
        val deHop1 = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "10.30.1.1", 1080)
        val deHop2 = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "10.30.2.2", 1080)
        val usHop = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.HTTP, "10.40.1.1", 8080)

        val traces = listOf(
            LocalProxyServer.HopTraceResult(irHop, 35, "5.5.5.5", "IR"),
            LocalProxyServer.HopTraceResult(excludedUsHop, 40, "198.51.100.99", "US"),
            LocalProxyServer.HopTraceResult(deHop1, 90, "185.1.1.1", "DE"),
            LocalProxyServer.HopTraceResult(deHop2, 95, "185.1.2.2", "DE"),
            LocalProxyServer.HopTraceResult(usHop, 60, "203.0.113.20", "US")
        )

        val pool = GeoBypassEngine.classifyQualifiedTraces(
            traces = traces,
            targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW,
            excludedEgressIps = setOf("198.51.100.99")
        )

        assertEquals(3, pool.cleanExits.size)
        assertEquals(listOf(usHop, deHop1, deHop2), pool.cleanExits)
        assertFalse(pool.cleanExits.contains(irHop))
        assertFalse(pool.cleanExits.contains(excludedUsHop))
        assertTrue(pool.relays.contains(irHop))
        assertTrue(pool.relays.contains(excludedUsHop))
    }

    @Test
    fun localProxyServer_probeHopTraceQueriesMockCloudflareTrace() {
        val traceServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val traceThread = thread(isDaemon = true) {
            while (!traceServer.isClosed) {
                val c = try { traceServer.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    c.use { sock ->
                        val ins = sock.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                        while (true) {
                            val line = ins.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val body = "fl=12f34\nip=203.0.113.77\nloc=DE\ntls=TLSv1.3\n"
                        val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body"
                        sock.getOutputStream().write(resp.toByteArray(StandardCharsets.UTF_8))
                        sock.getOutputStream().flush()
                    }
                }
            }
        }
        val hopServer = LocalProxyServer(requestedPort = 0, upstream = LocalProxyServer.Upstream.Direct)
        val hopPort = hopServer.start()
        try {
            val res = LocalProxyServer.probeHopTrace(
                hop = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "127.0.0.1", hopPort),
                timeoutMs = 3000,
                traceHost = "127.0.0.1",
                tracePort = traceServer.localPort,
                useTls = false
            )
            assertTrue("Expected probeHopTrace to succeed against loopback mock", res != null)
            assertEquals("203.0.113.77", res!!.exitIp)
            assertEquals("DE", res.countryCode)
        } finally {
            hopServer.close()
            traceServer.close()
            traceThread.interrupt()
        }
    }

    @Test
    fun discoverBestRouteLoop_excludesBlockedIpHarvestsSecondRoundAndUnlocksChain() {
        val blockedHop = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "198.51.100.10", 1080)
        val relayHop = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "198.51.100.20", 1080)
        val cleanExitHop = TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.HTTP, "203.0.113.55", 8080)

        val okAi = GeoBypassEngine.ServiceProbeResult(true, 200, 100, false, "https://aistudio.google.com/", "ok", appShellOk = true)
        val okFlow = GeoBypassEngine.ServiceProbeResult(true, 200, 120, false, "https://flow.google.com/", "ok", appShellOk = true)
        val geoBlockedFlow = GeoBypassEngine.ServiceProbeResult(false, 302, 80, true, "https://flow.google.com/unsupported-country", "Geo-blocked", appShellOk = false)

        fun blockedReport(ip: String, cc: String) = GeoBypassEngine.AccessProbeReport(
            routeLabel = "Blocked",
            egress = GeoBypassEngine.EgressInfo(ip, cc, cc, false, "trace"),
            flowCountrySupported = false,
            aiStudio = okAi,
            flow = geoBlockedFlow,
            headline = "Blocked",
            recommendation = "",
            accountsReachable = true
        )

        val round2Harvested = mutableListOf<Int>()
        val result = GeoBypassEngine.discoverBestRouteLoop(
            targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW,
            externalVpnActive = true,
            existingHops = listOf(blockedHop),
            listening = emptyList(),
            initialPool = listOf(blockedHop),
            allowHarvest = true,
            maxCandidates = 32,
            excludedEgressIps = emptySet(),
            excludeCurrentRoute = true,
            maxRounds = 3,
            hopQualifier = { batch ->
                val traces = batch.mapNotNull { hop ->
                    when (hop) {
                        blockedHop -> LocalProxyServer.HopTraceResult(hop, 40, "5.5.5.5", "IR")
                        relayHop -> LocalProxyServer.HopTraceResult(hop, 50, "6.6.6.6", "CN")
                        cleanExitHop -> LocalProxyServer.HopTraceResult(hop, 70, "203.0.113.55", "US")
                        else -> null
                    }
                }
                GeoBypassEngine.classifyQualifiedTraces(traces, GeoBypassEngine.TargetService.GOOGLE_FLOW, setOf("5.5.5.5"))
            },
            harvester = { round, _, _ ->
                round2Harvested.add(round)
                if (round >= 2) listOf(relayHop, cleanExitHop) else emptyList()
            },
            routeProber = { hops, label ->
                when {
                    hops == listOf(blockedHop) || hops.isEmpty() ->
                        blockedReport("5.5.5.5", "IR") to null
                    hops.lastOrNull() == cleanExitHop ->
                        GeoBypassEngine.AccessProbeReport(
                            routeLabel = label,
                            egress = GeoBypassEngine.EgressInfo("203.0.113.55", "US", "United States", false, "ipwho.is"),
                            flowCountrySupported = true,
                            aiStudio = okAi,
                            flow = okFlow,
                            headline = "Ready",
                            recommendation = "",
                            accountsReachable = true
                        ) to null
                    else ->
                        blockedReport("6.6.6.6", "CN") to null
                }
            }
        )

        assertTrue("Expected round >= 2 harvest to be triggered", round2Harvested.contains(2))
        assertTrue("Expected discovery to unlock Flow", result.success)
        assertEquals("203.0.113.55", result.report.egress?.ip)
        assertFalse("Must exclude baseline blocked IP", result.report.egress?.ip == "5.5.5.5")
    }

    @Test
    fun planRoute_bridgesRemoteHttpHopThroughLocalBridgeWhileLoopbackStaysDirect() {
        val remoteSettings = TunnelConfig.Settings(
            googleAccessMode = TunnelConfig.GoogleAccessMode.IN_APP_PROXY,
            upstreamProxyScheme = TunnelConfig.ProxyScheme.HTTP,
            upstreamProxyHost = "198.51.100.50",
            upstreamProxyPort = 8080,
            localBridgePort = 10850
        )
        val remotePlan = GeoBypassEngine.planRoute(
            settings = remoteSettings,
            externalVpnActive = false,
            dastyabVpnActive = false,
            runningBridgePort = null,
            detectedListeningHttpPort = null
        )
        assertTrue("Remote HTTP hop must be bridged locally", remotePlan.shouldStartLocalBridge)
        assertEquals(10850, remotePlan.webViewHttpProxyPort)

        val loopbackSettings = remoteSettings.copy(upstreamProxyHost = "127.0.0.1", upstreamProxyPort = 10809)
        val loopbackPlan = GeoBypassEngine.planRoute(
            settings = loopbackSettings,
            externalVpnActive = false,
            dastyabVpnActive = false,
            runningBridgePort = null,
            detectedListeningHttpPort = null
        )
        assertFalse("Single unauthenticated loopback HTTP hop should not start redundant bridge", loopbackPlan.shouldStartLocalBridge)
        assertEquals(10809, loopbackPlan.webViewHttpProxyPort)
    }
}
