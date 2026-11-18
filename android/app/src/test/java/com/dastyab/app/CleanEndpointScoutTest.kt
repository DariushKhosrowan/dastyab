package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Hermetic JVM unit tests for [CleanEndpointScout] CIDR sampling and VLESS Clean IP patching.
 */
class CleanEndpointScoutTest {

    @Test
    fun sampleIpsFromCidr_generatesValidUniqueIpsWithinSubnet() {
        val ips = CleanEndpointScout.sampleIpsFromCidr("104.16.0.0/16", 10, Random(42))
        assertEquals(10, ips.size)
        assertTrue(ips.all { it.startsWith("104.16.") && TunnelConfig.isValidIpv4(it) })
        assertTrue(CleanEndpointScout.sampleIpsFromCidr("not-a-cidr", 5).isEmpty())
    }

    @Test
    fun patchVlessWithCleanIp_replacesHostAndPreservesSniAndPath() {
        val original =
            "vless://11111111-2222-3333-4444-555555555555@old.worker.dev:443" +
                "?security=tls&type=ws&sni=my.sni.dev&path=%2Fws#MyWorker"
        val patched = CleanEndpointScout.patchVlessWithCleanIp(original, "104.16.12.34")
        assertNotNull(patched)
        val parsed = TunnelConfig.parseVlessUri(patched)
        assertNotNull(parsed)
        assertEquals("104.16.12.34", parsed!!.host)
        assertEquals(443, parsed.port)
        assertEquals("my.sni.dev", parsed.sni)
        assertEquals("/ws", parsed.path)
        assertEquals("MyWorker", parsed.label)

        assertNull(CleanEndpointScout.patchVlessWithCleanIp(original, "999.999.1.1"))
    }
}
