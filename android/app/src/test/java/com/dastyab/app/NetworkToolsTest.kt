package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [NetworkTools] Cloudflare trace parsing,
 * DNS sinkhole/poisoning IP detection, and Base64 subscription codec.
 */
class NetworkToolsTest {

    @Test
    fun parseCloudflareTrace_extractsFields() {
        val sample = """
            fl=123f45
            h=1.1.1.1
            ip=203.0.113.42
            loc=DE
            colo=FRA
            tls=TLSv1.3
            http=http/2
            warp=off
        """.trimIndent()

        val info = NetworkTools.parseCloudflareTrace(sample)
        assertEquals("203.0.113.42", info.ip)
        assertEquals("DE", info.location)
        assertEquals("FRA", info.colo)
        assertEquals("TLSv1.3", info.tls)
        assertEquals("http/2", info.http)
        assertEquals("off", info.warp)
    }

    @Test
    fun isSinkholeOrPrivateIp_detectsPoisonedIps() {
        assertTrue(NetworkTools.isSinkholeOrPrivateIp("10.10.34.35"))
        assertTrue(NetworkTools.isSinkholeOrPrivateIp("0.0.0.0"))
        assertTrue(NetworkTools.isSinkholeOrPrivateIp("127.0.0.1"))
        assertTrue(NetworkTools.isSinkholeOrPrivateIp("192.168.1.1"))
        assertFalse(NetworkTools.isSinkholeOrPrivateIp("104.16.132.229"))
        assertFalse(NetworkTools.isSinkholeOrPrivateIp("1.1.1.1"))
    }

    @Test
    fun base64EncodeDecode_roundTripsSubscriptionText() {
        val plain = "vless://uuid@example.com:443#Node1\ntrojan://pass@example.org:443#Node2"
        val encoded = NetworkTools.encodeBase64(plain)
        assertEquals(plain, NetworkTools.decodeBase64(encoded))
    }
}
