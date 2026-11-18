package com.dastyab.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hermetic JVM unit tests for [DnsPacketCodec] IPv4/UDP DNS query parsing,
 * response construction, RFC 1071 checksum verification, and QNAME extraction.
 */
class DnsPacketCodecTest {

    @Test
    fun parseAndBuildIpv4UdpDnsRoundTrip() {
        val dnsQuery = CleanEndpointScout.buildMinimalDnsQuery()
        val req = DnsPacketCodec.DnsRequest(
            srcIp = byteArrayOf(10, 215.toByte(), 0, 1),
            dstIp = byteArrayOf(10, 215.toByte(), 0, 2),
            srcPort = 54321,
            dstPort = 53,
            dnsPayload = dnsQuery
        )

        // Build a query packet by swapping src/dst into an IPv4+UDP frame
        val clientPacket = buildClientQueryPacket(req)
        val parsed = DnsPacketCodec.parseIpv4UdpDnsQuery(clientPacket, clientPacket.size)
        assertNotNull(parsed)
        assertArrayEquals(req.srcIp, parsed!!.srcIp)
        assertArrayEquals(req.dstIp, parsed.dstIp)
        assertEquals(54321, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(dnsQuery, parsed.dnsPayload)
        assertEquals("one.one.one.one", DnsPacketCodec.extractQueryDomain(parsed.dnsPayload))

        // Build response packet and verify IPv4 header checksum & swapped endpoints
        val fakeDnsResponse = dnsQuery.copyOf().apply { this[2] = 0x81.toByte() }
        val responsePacket = DnsPacketCodec.buildIpv4UdpDnsResponse(parsed, fakeDnsResponse)
        assertEquals(20 + 8 + fakeDnsResponse.size, responsePacket.size)
        assertEquals(0x45, responsePacket[0].toInt() and 0xFF)
        assertEquals(17, responsePacket[9].toInt() and 0xFF) // UDP
        // Src IP of response == original dst IP (10.215.0.2)
        assertArrayEquals(req.dstIp, responsePacket.copyOfRange(12, 16))
        // Dst IP of response == original src IP (10.215.0.1)
        assertArrayEquals(req.srcIp, responsePacket.copyOfRange(16, 20))
        // Checksum validation
        val expectedChecksum = DnsPacketCodec.ipv4HeaderChecksum(responsePacket, 0, 20)
        val storedChecksum = ((responsePacket[10].toInt() and 0xFF) shl 8) or (responsePacket[11].toInt() and 0xFF)
        assertEquals(expectedChecksum, storedChecksum)
    }

    @Test
    fun parseRejectsNonDnsOrTruncatedPackets() {
        assertNull(DnsPacketCodec.parseIpv4UdpDnsQuery(ByteArray(10)))
        val badProto = ByteArray(48).apply {
            this[0] = 0x45.toByte()
            this[9] = 6.toByte() // TCP, not UDP
        }
        assertNull(DnsPacketCodec.parseIpv4UdpDnsQuery(badProto))
    }

    private fun buildClientQueryPacket(req: DnsPacketCodec.DnsRequest): ByteArray {
        val udpLen = 8 + req.dnsPayload.size
        val totalLen = 20 + udpLen
        val buf = ByteArray(totalLen)
        buf[0] = 0x45.toByte()
        buf[2] = ((totalLen ushr 8) and 0xFF).toByte()
        buf[3] = (totalLen and 0xFF).toByte()
        buf[8] = 64.toByte()
        buf[9] = 17.toByte()
        System.arraycopy(req.srcIp, 0, buf, 12, 4)
        System.arraycopy(req.dstIp, 0, buf, 16, 4)
        buf[20] = ((req.srcPort ushr 8) and 0xFF).toByte()
        buf[21] = (req.srcPort and 0xFF).toByte()
        buf[22] = ((req.dstPort ushr 8) and 0xFF).toByte()
        buf[23] = (req.dstPort and 0xFF).toByte()
        buf[24] = ((udpLen ushr 8) and 0xFF).toByte()
        buf[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(req.dnsPayload, 0, buf, 28, req.dnsPayload.size)
        return buf
    }
}
