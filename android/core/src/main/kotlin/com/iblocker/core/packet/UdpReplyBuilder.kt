package com.iblocker.core.packet

/**
 * Builds the raw IP reply packet for a request that arrived through the tun
 * device: the 5-tuple is swapped and checksums are computed from scratch.
 */
object UdpReplyBuilder {

    fun reply(request: ParsedUdpPacket, payload: ByteArray): ByteArray? = when (request.ipVersion) {
        4 -> ipv4Reply(request, payload)
        6 -> ipv6Reply(request, payload)
        else -> null
    }

    private fun ipv4Reply(request: ParsedUdpPacket, payload: ByteArray): ByteArray? {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        if (totalLength > 0xFFFF ||
            request.sourceAddress.size != 4 ||
            request.destinationAddress.size != 4
        ) {
            return null
        }

        val header = ByteArray(20)
        header[0] = 0x45
        header[1] = 0x00
        header[2] = ((totalLength shr 8) and 0xFF).toByte()
        header[3] = (totalLength and 0xFF).toByte()
        header[4] = 0x00                       // identification (DF set, so 0 is valid per RFC 6864)
        header[5] = 0x00
        header[6] = 0x40                       // flags: DF
        header[7] = 0x00
        header[8] = 64                         // TTL
        header[9] = 17                         // protocol UDP
        header[10] = 0x00                      // header checksum (placeholder)
        header[11] = 0x00
        request.destinationAddress.copyInto(header, 12)  // our reply source
        request.sourceAddress.copyInto(header, 16)       // back to the client

        val headerChecksum = InternetChecksum.ipv4Header(header)
        header[10] = ((headerChecksum shr 8) and 0xFF).toByte()
        header[11] = (headerChecksum and 0xFF).toByte()

        val segment = udpSegment(
            sourcePort = request.destinationPort,
            destinationPort = request.sourcePort,
            payload = payload,
            ipVersion = 4,
            sourceAddress = request.destinationAddress,
            destinationAddress = request.sourceAddress,
        )

        return header + segment
    }

    private fun ipv6Reply(request: ParsedUdpPacket, payload: ByteArray): ByteArray? {
        val udpLength = 8 + payload.size
        if (udpLength > 0xFFFF ||
            request.sourceAddress.size != 16 ||
            request.destinationAddress.size != 16
        ) {
            return null
        }

        val header = ByteArray(40)
        header[0] = 0x60
        header[4] = ((udpLength shr 8) and 0xFF).toByte()
        header[5] = (udpLength and 0xFF).toByte()
        header[6] = 17                          // next header UDP
        header[7] = 64                          // hop limit
        request.destinationAddress.copyInto(header, 8)
        request.sourceAddress.copyInto(header, 24)

        val segment = udpSegment(
            sourcePort = request.destinationPort,
            destinationPort = request.sourcePort,
            payload = payload,
            ipVersion = 6,
            sourceAddress = request.destinationAddress,
            destinationAddress = request.sourceAddress,
        )

        return header + segment
    }

    private fun udpSegment(
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        ipVersion: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val segment = ByteArray(udpLength)
        segment[0] = ((sourcePort shr 8) and 0xFF).toByte()
        segment[1] = (sourcePort and 0xFF).toByte()
        segment[2] = ((destinationPort shr 8) and 0xFF).toByte()
        segment[3] = (destinationPort and 0xFF).toByte()
        segment[4] = ((udpLength shr 8) and 0xFF).toByte()
        segment[5] = (udpLength and 0xFF).toByte()
        segment[6] = 0x00                       // checksum (placeholder)
        segment[7] = 0x00
        payload.copyInto(segment, 8)

        val checksum = InternetChecksum.udp(ipVersion, sourceAddress, destinationAddress, segment)
        segment[6] = ((checksum shr 8) and 0xFF).toByte()
        segment[7] = (checksum and 0xFF).toByte()
        return segment
    }
}
