package com.dastyab.app

/**
 * DnsPacketCodec — pure-Kotlin IPv4/UDP DNS packet parser and response builder
 * used by [DastyabVpnService] in DNS Blackout (WhiteDNS / DoH) mode.
 *
 * Includes IPv6 AAAA suppression helper (RFC 4074 NODATA) to eliminate carrier IPv6
 * bypass leaks when IPv4 proxy chains are active.
 *
 * Zero Android dependencies so every checksum and packet boundary is unit-testable on the JVM.
 */
object DnsPacketCodec {

    private const val IP_PROTO_UDP = 17
    private const val DNS_PORT = 53
    private const val MIN_IPV4_HEADER = 20
    private const val UDP_HEADER_LEN = 8
    const val QTYPE_A = 1
    const val QTYPE_AAAA = 28
    const val EDNS0_OPT_TYPE = 41
    const val EDNS0_PADDING_OPTION_CODE = 12
    const val DEFAULT_DOH_PAD_BLOCK_SIZE = 128

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

    /** Parses the exact QTYPE of question 1 in a raw DNS query (`1` = A, `28` = AAAA). */
    fun extractQueryType(dnsPayload: ByteArray): Int {
        if (dnsPayload.size < 17) return 0
        var pos = 12
        var guard = 0
        while (pos < dnsPayload.size && guard < 64) {
            val labelLen = dnsPayload[pos].toInt() and 0xFF
            if (labelLen == 0) {
                pos++
                break
            }
            if ((labelLen and 0xC0) != 0) {
                pos += 2
                break
            }
            pos += 1 + labelLen
            guard++
        }
        if (pos + 4 > dnsPayload.size) return 0
        return readUInt16(dnsPayload, pos)
    }

    /**
     * Builds an RFC 4074 NOERROR / NODATA empty DNS reply for suppressing IPv6 (`AAAA`) queries
     * cleanly when an IPv4-only proxy bridge is active so apps never leak out over carrier IPv6.
     */
    fun buildEmptyNoDataResponse(dnsQuery: ByteArray): ByteArray? {
        if (dnsQuery.size < 17) return null
        val qdCount = readUInt16(dnsQuery, 4)
        if (qdCount != 1) return null
        var qEnd = 12
        var guard = 0
        while (qEnd < dnsQuery.size && guard < 64) {
            val labelLen = dnsQuery[qEnd].toInt() and 0xFF
            if (labelLen == 0) {
                qEnd++
                break
            }
            if ((labelLen and 0xC0) != 0) {
                qEnd += 2
                break
            }
            qEnd += 1 + labelLen
            guard++
        }
        val questionEnd = qEnd + 4 // QTYPE + QCLASS
        if (questionEnd > dnsQuery.size) return null

        val out = ByteArray(questionEnd)
        System.arraycopy(dnsQuery, 0, out, 0, questionEnd)
        val rdFlag = dnsQuery[2].toInt() and 0x01
        out[2] = (0x80 or rdFlag).toByte() // QR=1 (response), preserve RD
        out[3] = 0x80.toByte()            // RA=1, RCODE=0 (NOERROR)
        writeUInt16(out, 4, 1)             // QDCOUNT = 1
        writeUInt16(out, 6, 0)             // ANCOUNT = 0
        writeUInt16(out, 8, 0)             // NSCOUNT = 0
        writeUInt16(out, 10, 0)            // ARCOUNT = 0
        return out
    }

    /**
     * Pads a standard DNS wire query with an RFC 6891 + RFC 7830/8467 EDNS0 OPT Padding record
     * (`OPTION-CODE = 12`) so outbound DoH frames align to [blockSize] boundaries + [extraJitterBytes],
     * defeating DPI packet-size fingerprinting while staying 100% DNS-compliant.
     */
    fun padDnsQueryEdns0(
        dnsQuery: ByteArray,
        blockSize: Int = DEFAULT_DOH_PAD_BLOCK_SIZE,
        extraJitterBytes: Int = 0
    ): ByteArray {
        if (dnsQuery.size < 17) return dnsQuery.copyOf()
        val arCount = readUInt16(dnsQuery, 10)
        if (arCount != 0) return dnsQuery.copyOf() // Preserve pre-existing EDNS0 / TSIG records cleanly

        val cleanBlock = blockSize.coerceIn(64, 512)
        val optOverhead = 11 + 4 // Root(1) + TYPE(2) + CLASS(2) + TTL(4) + RDLENGTH(2) + OptCode(2) + OptLen(2)
        val minTotal = dnsQuery.size + optOverhead
        val rem = minTotal % cleanBlock
        val alignedTarget = if (rem == 0) minTotal else minTotal + (cleanBlock - rem)
        val jitter = extraJitterBytes.coerceIn(0, 64)
        val targetSize = (alignedTarget + jitter).coerceAtLeast(minTotal)
        val padLen = (targetSize - minTotal).coerceAtLeast(0)
        val rdLen = 4 + padLen

        val out = ByteArray(dnsQuery.size + 11 + rdLen)
        System.arraycopy(dnsQuery, 0, out, 0, dnsQuery.size)
        writeUInt16(out, 10, 1) // ARCOUNT = 1

        var p = dnsQuery.size
        out[p++] = 0x00 // Root domain '.'
        writeUInt16(out, p, EDNS0_OPT_TYPE); p += 2
        writeUInt16(out, p, 1232); p += 2 // UDP payload size
        out[p++] = 0x00; out[p++] = 0x00; out[p++] = 0x00; out[p++] = 0x00 // Extended RCODE / Version / Z
        writeUInt16(out, p, rdLen); p += 2
        writeUInt16(out, p, EDNS0_PADDING_OPTION_CODE); p += 2
        writeUInt16(out, p, padLen); p += 2
        for (i in 0 until padLen) {
            out[p + i] = ((i * 31 + dnsQuery.size) and 0x00).toByte() // RFC 7830 zeros padding bytes
        }
        return out
    }

    /** Returns true when [dnsPacket] declares at least 1 additional record and carries an EDNS0 OPT tag (`0x0029`). */
    fun hasEdns0OptRecord(dnsPacket: ByteArray): Boolean {
        if (dnsPacket.size < 23) return false
        val arCount = readUInt16(dnsPacket, 10)
        if (arCount < 1) return false
        for (i in 12 until dnsPacket.size - 10) {
            if (dnsPacket[i] == 0x00.toByte() && readUInt16(dnsPacket, i + 1) == EDNS0_OPT_TYPE) {
                return true
            }
        }
        return false
    }

    private fun readUInt16(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }

    private fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }
}
