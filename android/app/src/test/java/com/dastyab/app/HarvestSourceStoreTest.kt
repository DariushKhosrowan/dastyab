package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hermetic JVM unit tests for [HarvestSourceStore] GitHub blob-to-raw conversion,
 * Telegram channel normalization, bulk parsing, and proxy URI host/port extraction.
 */
class HarvestSourceStoreTest {

    @Test
    fun normalizeGithubUrl_convertsBlobUrlsToRaw() {
        val blob = "https://github.com/WhiteDNS/WhiteVPN/blob/main/configs/sub.txt"
        assertEquals(
            "https://raw.githubusercontent.com/WhiteDNS/WhiteVPN/main/configs/sub.txt",
            HarvestSourceStore.normalizeGithubUrl(blob)
        )
        val alreadyRaw = "https://raw.githubusercontent.com/a/b/main/c.txt"
        assertEquals(alreadyRaw, HarvestSourceStore.normalizeGithubUrl(alreadyRaw))
        assertNull(HarvestSourceStore.normalizeGithubUrl("not-a-url"))
    }

    @Test
    fun normalizeTelegramChannel_handlesAtAndUrls() {
        assertEquals("v2rayng_org", HarvestSourceStore.normalizeTelegramChannel("@v2rayng_org"))
        assertEquals("PrivateVPNs", HarvestSourceStore.normalizeTelegramChannel("https://t.me/s/PrivateVPNs"))
        assertEquals("DirectVPN", HarvestSourceStore.normalizeTelegramChannel("t.me/DirectVPN/1234"))
        assertEquals("whitedns", HarvestSourceStore.normalizeTelegramChannel("Telegram.me/whitedns"))
        assertEquals("whitedns", HarvestSourceStore.normalizeTelegramChannel("https://telegram.me/s/whitedns"))
        assertNull(HarvestSourceStore.normalizeTelegramChannel("a"))
        assertNull(HarvestSourceStore.normalizeTelegramChannel("bad channel!"))
    }

    @Test
    fun parseBulkUrlsAndChannels_deduplicatesAndNormalizes() {
        val urls = HarvestSourceStore.parseBulkGithubUrls(
            "https://github.com/u/r/blob/main/a.txt\nhttps://raw.githubusercontent.com/u/r/main/a.txt, invalid"
        )
        assertEquals(listOf("https://raw.githubusercontent.com/u/r/main/a.txt"), urls)

        val channels = HarvestSourceStore.parseBulkTelegramChannels("@chan_one, https://t.me/s/chan_two\nchan_one")
        assertEquals(listOf("chan_one", "chan_two"), channels)
    }

    @Test
    fun extractHostAndPort_supportsAllProtocols() {
        assertEquals(
            "edge.example.com" to 443,
            HarvestSourceStore.extractHostAndPort("vless://uuid@edge.example.com:443?security=tls")
        )
        assertEquals(
            "tr.example.com" to 8443,
            HarvestSourceStore.extractHostAndPort("trojan://pass@tr.example.com:8443#Node")
        )
        assertEquals(
            "162.159.192.1" to 2408,
            HarvestSourceStore.extractHostAndPort("wireguard://privKey@162.159.192.1:2408?publickey=pub#WARP")
        )
        assertEquals(
            "npv.example.com" to 443,
            HarvestSourceStore.extractHostAndPort("npv4://user@npv.example.com:443#NPV")
        )
        assertEquals(
            "185.1.2.3" to 8443,
            HarvestSourceStore.extractHostAndPort("tg://proxy?server=185.1.2.3&port=8443&secret=dd00")
        )
    }
}
