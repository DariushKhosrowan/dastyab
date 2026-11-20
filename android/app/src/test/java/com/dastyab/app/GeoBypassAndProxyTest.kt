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

    @Test
    fun localProxyServer_threeHopChainWithHttpAndSocks5AuthOnLoopback() {
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

        // Hop 3 (Exit): LocalProxyServer Direct
        val exitProxy = LocalProxyServer(requestedPort = 0, upstream = LocalProxyServer.Upstream.Direct)
        val exitPort = exitProxy.start()

        // Hop 2 (Middle): Minimal SOCKS5 server requiring RFC 1929 user/pass ("u2":"p2") forwarding to exitPort
        val socksAuthServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val socksAuthThread = thread(isDaemon = true) {
            while (!socksAuthServer.isClosed) {
                val c = try { socksAuthServer.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    c.use { sock ->
                        val ins = DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        val ver = ins.readUnsignedByte()
                        val nm = ins.readUnsignedByte()
                        if (ver != 5 || nm <= 0) return@use
                        val methods = ByteArray(nm); ins.readFully(methods)
                        if (!methods.contains(0x02.toByte())) {
                            out.write(byteArrayOf(0x05, 0xFF.toByte())); return@use
                        }
                        out.write(byteArrayOf(0x05, 0x02)); out.flush()
                        val aver = ins.readUnsignedByte()
                        val ulen = ins.readUnsignedByte(); val ub = ByteArray(ulen); ins.readFully(ub)
                        val plen = ins.readUnsignedByte(); val pb = ByteArray(plen); ins.readFully(pb)
                        if (aver != 1 || String(ub, StandardCharsets.UTF_8) != "u2" || String(pb, StandardCharsets.UTF_8) != "p2") {
                            out.write(byteArrayOf(0x01, 0x01)); out.flush(); return@use
                        }
                        out.write(byteArrayOf(0x01, 0x00)); out.flush()
                        val hdr = ByteArray(4); ins.readFully(hdr)
                        val atyp = hdr[3].toInt() and 0xFF
                        val host = if (atyp == 3) {
                            val hl = ins.readUnsignedByte(); val hb = ByteArray(hl); ins.readFully(hb); String(hb, StandardCharsets.US_ASCII)
                        } else {
                            val ab = ByteArray(4); ins.readFully(ab); InetAddress.getByAddress(ab).hostAddress
                        }
                        val portB = ByteArray(2); ins.readFully(portB)
                        val tPort = ((portB[0].toInt() and 0xFF) shl 8) or (portB[1].toInt() and 0xFF)
                        Socket("127.0.0.1", exitPort).use { up ->
                            val uo = up.getOutputStream(); val ui = DataInputStream(up.getInputStream())
                            uo.write(byteArrayOf(0x05, 0x01, 0x00)); uo.flush()
                            val gr = ByteArray(2); ui.readFully(gr)
                            val hb = host.toByteArray(StandardCharsets.US_ASCII)
                            val cr = ByteArray(7 + hb.size)
                            cr[0] = 5; cr[1] = 1; cr[2] = 0; cr[3] = 3; cr[4] = hb.size.toByte()
                            System.arraycopy(hb, 0, cr, 5, hb.size)
                            cr[5 + hb.size] = ((tPort shr 8) and 0xFF).toByte()
                            cr[6 + hb.size] = (tPort and 0xFF).toByte()
                            uo.write(cr); uo.flush()
                            val rep = ByteArray(10); ui.readFully(rep)
                            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                            val t = thread(isDaemon = true) { runCatching { ins.copyTo(uo); up.shutdownOutput() } }
                            runCatching { ui.copyTo(out); out.flush() }
                            t.join(1000)
                        }
                    }
                }
            }
        }

        // Hop 1 (Entry): Minimal HTTP CONNECT server requiring Basic auth ("u1:p1") forwarding TCP to socksAuthServer
        val httpAuthServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val httpAuthThread = thread(isDaemon = true) {
            while (!httpAuthServer.isClosed) {
                val c = try { httpAuthServer.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    c.use { sock ->
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
                                it.contains("dTE6cDE=") // base64("u1:p1")
                        }
                        if (!authOk) {
                            out.write("HTTP/1.1 407 Proxy Auth Required\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            out.flush()
                            return@use
                        }
                        val targetAuth = hdrText.lines().first().split(' ')[1]
                        val (tHost, tPort) = LocalProxyServer.parseHostPort(targetAuth, 443)!!
                        Socket(tHost, tPort).use { up ->
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            out.flush()
                            val uo = up.getOutputStream(); val ui = up.getInputStream()
                            val t = thread(isDaemon = true) { runCatching { ins.copyTo(uo); up.shutdownOutput() } }
                            runCatching { ui.copyTo(out); out.flush() }
                            t.join(1000)
                        }
                    }
                }
            }
        }

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
            assertEquals("three-hop-ok", echoed)
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
}
