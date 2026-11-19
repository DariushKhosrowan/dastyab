package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
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
}
