package com.dastyab.app

/**
 * DnsPacketCodec — pure-Kotlin IPv4/UDP DNS packet parser and response builder
 * used by [DastyabVpnService] in DNS Blackout (WhiteDNS / DoH) mode.
 *
 * Zero Android dependencies so every checksum and packet boundary is unit-testable on the JVM.
 */
object DnsPacketCodec {

    private const val IP_PROTO_UDP = 17
    private const val DNS_PORT = 53
    private const val MIN_IPV4_HEADER = 20
    private const val UDP_HEADER_LEN = 8

    data class DnsRequest(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray
    )

    /**
     * Extracts a UDP port-53 DNS query from a raw IPv4 packet read from a TUN interface.
     * Returns null for non-IPv4, non-UDP, non-port-53, or malformed packets.
     */
    fun parseIpv4UdpDnsQuery(packet: ByteArray, length: Int = packet.size): DnsRequest? {
        if (length < MIN_IPV4_HEADER + UDP_HEADER_LEN + 12 || length > packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < MIN_IPV4_HEADER || length < ihl + UDP_HEADER_LEN + 12) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != IP_PROTO_UDP) return null

        val srcPort = readUInt16(packet, ihl)
        val dstPort = readUInt16(packet, ihl + 2)
        if (dstPort != DNS_PORT) return null

        val udpLength = readUInt16(packet, ihl + 4)
        val payloadLen = udpLength - UDP_HEADER_LEN
        val payloadStart = ihl + UDP_HEADER_LEN
        if (payloadLen < 12 || payloadStart + payloadLen > length) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val dnsPayload = packet.copyOfRange(payloadStart, payloadStart + payloadLen)

        return DnsRequest(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            dnsPayload = dnsPayload
        )
    }

    /**
     * Wraps a raw DNS wire response into a valid IPv4 + UDP packet addressed back
     * to the original requester ([request.srcIp]:[request.srcPort]).
     */
    fun buildIpv4UdpDnsResponse(request: DnsRequest, dnsResponse: ByteArray): ByteArray {
        val udpLen = UDP_HEADER_LEN + dnsResponse.size
        val totalLen = MIN_IPV4_HEADER + udpLen
        val out = ByteArray(totalLen)

        // IPv4 Header (20 bytes)
        out[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        out[1] = 0x00.toByte() // DSCP/ECN
        writeUInt16(out, 2, totalLen)
        writeUInt16(out, 4, (request.srcPort xor 0x5A5A) and 0xFFFF) // Identification
        out[6] = 0x40.toByte() // Don't Fragment
        out[7] = 0x00.toByte()
        out[8] = 64.toByte() // TTL
        out[9] = IP_PROTO_UDP.toByte()
        // Checksum bytes 10..11 filled below
        System.arraycopy(request.dstIp, 0, out, 12, 4) // Response src = original dst
        System.arraycopy(request.srcIp, 0, out, 16, 4) // Response dst = original src

        val ipChecksum = ipv4HeaderChecksum(out, 0, MIN_IPV4_HEADER)
        writeUInt16(out, 10, ipChecksum)

        // UDP Header (8 bytes)
        writeUInt16(out, MIN_IPV4_HEADER, request.dstPort)     // Src port (53)
        writeUInt16(out, MIN_IPV4_HEADER + 2, request.srcPort) // Dst port (client ephemeral)
        writeUInt16(out, MIN_IPV4_HEADER + 4, udpLen)
        writeUInt16(out, MIN_IPV4_HEADER + 6, 0) // UDP checksum optional in IPv4

        // DNS Payload
        System.arraycopy(dnsResponse, 0, out, MIN_IPV4_HEADER + UDP_HEADER_LEN, dnsResponse.size)
        return out
    }

    /** Standard RFC 1071 one's-complement IPv4 header checksum. */
    fun ipv4HeaderChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end) {
            if (i == offset + 10) {
                i += 2
                continue
            }
            val hi = buf[i].toInt() and 0xFF
            val lo = if (i + 1 < end) buf[i + 1].toInt() and 0xFF else 0
            sum += (hi shl 8) or lo
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            i += 2
        }
        return sum.inv() and 0xFFFF
    }

    /** Extracts a human-readable QNAME (domain) from a DNS wire query for logging/diagnostics. */
    fun extractQueryDomain(dnsPayload: ByteArray): String {
        if (dnsPayload.size < 13) return ""
        val sb = StringBuilder()
        var pos = 12
        var guard = 0
        while (pos < dnsPayload.size && guard < 64) {
            val labelLen = dnsPayload[pos].toInt() and 0xFF
            if (labelLen == 0) break
            if ((labelLen and 0xC0) != 0) break // Compression pointer
            pos++
            if (pos + labelLen > dnsPayload.size) break
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until labelLen) {
                sb.append((dnsPayload[pos + k].toInt() and 0xFF).toChar())
            }
            pos += labelLen
            guard++
        }
        return sb.toString()
    }

    private fun readUInt16(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }

    private fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }
}
