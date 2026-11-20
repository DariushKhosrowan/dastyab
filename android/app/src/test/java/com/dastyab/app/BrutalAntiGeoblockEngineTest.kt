package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [BrutalAntiGeoblockEngine] ("Lion") anti-fingerprint headers,
 * ASN/WARP/Tor reputation scoring, country geo-alignment profiles, and WebView anti-leak shields.
 */
class BrutalAntiGeoblockEngineTest {

    @Test
    fun getBrutalHeadersMap_doesNotLeakAutomationOrSpoofedForwardingHeaders() {
        val headers = BrutalAntiGeoblockEngine.getBrutalHeadersMap("US")
        val keysLower = headers.keys.map { it.lowercase() }.toSet()

        assertFalse("Must not inject CF-Connecting-IP spoofing", keysLower.contains("cf-connecting-ip"))
        assertFalse("Must not inject True-Client-IP spoofing", keysLower.contains("true-client-ip"))
        assertFalse("Must not inject forged Via: Google", keysLower.contains("via"))
        assertFalse("Must not inject X-Forwarded-For with public DNS IPs", keysLower.contains("x-forwarded-for"))

        val allValues = headers.values.joinToString(" ")
        assertFalse("Must not contain identifiable bot token", allValues.contains("DastyabBrutalLion"))
        assertTrue(headers["Accept-Language"]?.contains("en-US") == true)
    }

    @Test
    fun classifyExitReputation_distinguishesResidentialDatacenterAndWarp() {
        assertEquals(
            BrutalAntiGeoblockEngine.ExitReputationTier.RESIDENTIAL_ISP,
            BrutalAntiGeoblockEngine.classifyExitReputation(org = "Comcast Cable Communications", isp = "Comcast")
        )
        assertEquals(
            BrutalAntiGeoblockEngine.ExitReputationTier.HOSTING_HIGH_RISK,
            BrutalAntiGeoblockEngine.classifyExitReputation(org = "Hetzner Online GmbH", isp = "Hetzner")
        )
        assertEquals(
            BrutalAntiGeoblockEngine.ExitReputationTier.HOSTING_HIGH_RISK,
            BrutalAntiGeoblockEngine.classifyExitReputation(org = "Amazon.com, Inc.", isHosting = true)
        )
        assertEquals(
            BrutalAntiGeoblockEngine.ExitReputationTier.WARP_OR_TOR,
            BrutalAntiGeoblockEngine.classifyExitReputation(org = "Cloudflare, Inc.", warpStatus = "on")
        )
        assertEquals(
            BrutalAntiGeoblockEngine.ExitReputationTier.WARP_OR_TOR,
            BrutalAntiGeoblockEngine.classifyExitReputation(org = "AS13335", isTor = true)
        )
        assertEquals(
            BrutalAntiGeoblockEngine.ExitReputationTier.CLEAN_NON_DC,
            BrutalAntiGeoblockEngine.classifyExitReputation(org = "University of Michigan", isp = "Merit Network")
        )
    }

    @Test
    fun buildWebViewAntiLeakScript_alignsTimezoneLocaleAndBlocksWebRtcAndGeolocation() {
        val usScript = BrutalAntiGeoblockEngine.buildWebViewAntiLeakScript("US")
        assertTrue(usScript.contains("America/New_York"))
        assertTrue(usScript.contains("en-US"))
        assertTrue(usScript.contains("RTCPeerConnection"))
        assertTrue(usScript.contains("webkitRTCPeerConnection"))
        assertTrue(usScript.contains("navigator.geolocation"))
        assertTrue(usScript.contains("return 300"))

        val deScript = BrutalAntiGeoblockEngine.buildWebViewAntiLeakScript("DE")
        assertTrue(deScript.contains("Europe/Berlin"))
        assertTrue(deScript.contains("de-DE"))
        assertTrue(deScript.contains("return -60"))
    }

    @Test
    fun localProxyServer_tlsClientHelloDesyncSlicesPreserveTotalLengthAndDetectHandshake() {
        val clientHello = ByteArray(180).apply {
            this[0] = 0x16.toByte()
            this[1] = 0x03.toByte()
            this[2] = 0x01.toByte()
            this[5] = 0x01.toByte() // HandshakeType.client_hello
        }
        assertTrue(LocalProxyServer.isTlsClientHello(clientHello, clientHello.size))
        assertFalse(LocalProxyServer.isTlsClientHello("GET / HTTP/1.1\r\n".toByteArray(), 16))

        val slices = LocalProxyServer.splitTlsClientHelloSlices(clientHello.size)
        assertTrue("ClientHello must split into >= 3 TCP segments", slices.size >= 3)
        var covered = 0
        var expectedStart = 0
        for (r in slices) {
            assertEquals(expectedStart, r.first)
            val len = r.last - r.first + 1
            assertTrue(len > 0)
            covered += len
            expectedStart = r.last + 1
        }
        assertEquals(clientHello.size, covered)
    }
}
