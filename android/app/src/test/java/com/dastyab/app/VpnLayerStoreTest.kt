package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [VpnLayerStore] default layers, subtitles, and probe targets.
 */
class VpnLayerStoreTest {

    @Test
    fun defaultLayers_coverSmartGlobalSplitAndDnsBlackout() {
        val defaults = VpnLayerStore.defaultLayers()
        assertEquals(4, defaults.size)
        val modes = defaults.map { it.mode }.toSet()
        assertTrue(modes.contains(TunnelConfig.Mode.SMART))
        assertTrue(modes.contains(TunnelConfig.Mode.GLOBAL))
        assertTrue(modes.contains(TunnelConfig.Mode.SPLIT_INCLUDE))
        assertTrue(modes.contains(TunnelConfig.Mode.DNS_BLACKOUT))
    }

    @Test
    fun probeTarget_extractsVlessHostAndPort() {
        val layer = VpnLayerStore.VpnLayer(
            name = "Worker Edge",
            mode = TunnelConfig.Mode.SMART,
            configUri = "vless://11111111-2222-3333-4444-555555555555@edge.example.com:8443?security=tls#Edge"
        )
        assertEquals("edge.example.com" to 8443, layer.probeTarget())
        assertTrue(layer.subtitle().contains("edge.example.com:8443"))
    }

    @Test
    fun probeTarget_usesDohHostForDnsBlackoutMode() {
        val layer = VpnLayerStore.VpnLayer(
            name = "Blackout DoH",
            mode = TunnelConfig.Mode.DNS_BLACKOUT,
            dohUrl = "https://9.9.9.9/dns-query"
        )
        assertEquals("9.9.9.9" to 443, layer.probeTarget())
        assertTrue(layer.subtitle().contains("9.9.9.9/dns-query"))
    }
}
