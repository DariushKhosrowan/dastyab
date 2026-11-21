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

    @Test
    fun sampleNeighborhoodIps_clustersAroundWinningSeedSlash24() {
        val neighbors = CleanEndpointScout.sampleNeighborhoodIps(
            seedIps = listOf("104.16.132.50"),
            count = 10,
            rng = Random(7)
        )
        assertEquals(10, neighbors.size)
        assertEquals("104.16.132.50", neighbors.first())
        assertTrue(neighbors.all { it.startsWith("104.16.") && TunnelConfig.isValidIpv4(it) })
        assertTrue(CleanEndpointScout.sampleNeighborhoodIps(listOf("bad-ip"), 5).isEmpty())
    }

    @Test
    fun rankRegionalBenchmarks_prioritizesSub300msAndSummarizesRegions() {
        val usSlow = CleanEndpointScout.RegionalTarget("us1", CleanEndpointScout.EdgeRegion.US, "US Slow", "1.1.1.1", 443)
        val euFast = CleanEndpointScout.RegionalTarget("eu1", CleanEndpointScout.EdgeRegion.EU, "EU Fast", "9.9.9.9", 443)
        val asiaDown = CleanEndpointScout.RegionalTarget("as1", CleanEndpointScout.EdgeRegion.ASIA, "Asia Down", "8.8.4.4", 443)

        val ranked = CleanEndpointScout.rankRegionalBenchmarks(
            listOf(
                CleanEndpointScout.RegionBenchmarkResult(asiaDown, -1L, reachable = false),
                CleanEndpointScout.RegionBenchmarkResult(usSlow, 420L, reachable = true),
                CleanEndpointScout.RegionBenchmarkResult(euFast, 115L, reachable = true)
            )
        )
        assertEquals(2, ranked.size)
        assertEquals("eu1", ranked[0].target.id)
        assertTrue(ranked[0].within300Ms)
        assertEquals("us1", ranked[1].target.id)
        assertEquals(false, ranked[1].within300Ms)

        val byRegion = CleanEndpointScout.summarizeFastestByRegion(ranked)
        assertEquals(2, byRegion.size)
        assertEquals(115L, byRegion[CleanEndpointScout.EdgeRegion.EU]?.latencyMs)
        val digest = CleanEndpointScout.formatRegionBenchmarkSummary(ranked)
        assertTrue(digest.contains("EU 115ms✓"))
        assertTrue(digest.contains("US 420ms!"))
    }
}
