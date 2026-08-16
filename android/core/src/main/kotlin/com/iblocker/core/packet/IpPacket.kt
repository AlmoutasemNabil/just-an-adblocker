package com.iblocker.core.packet

/** A UDP datagram extracted from a raw IP packet as delivered by the tun device. */
class ParsedUdpPacket(
    val ipVersion: Int,
    /** 4 bytes for IPv4, 16 for IPv6. */
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedUdpPacket) return false
        return ipVersion == other.ipVersion &&
            sourceAddress.contentEquals(other.sourceAddress) &&
            destinationAddress.contentEquals(other.destinationAddress) &&
            sourcePort == other.sourcePort &&
            destinationPort == other.destinationPort &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = ipVersion
        result = 31 * result + sourceAddress.contentHashCode()
        result = 31 * result + destinationAddress.contentHashCode()
        result = 31 * result + sourcePort
        result = 31 * result + destinationPort
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Parses raw IP packets from the tun device into UDP datagrams.
 *
 * Anything that is not a complete, unfragmented UDP packet returns null and
 * is dropped by the caller: DNS queries are far below fragmentation size,
 * and TCP:53 is intentionally unsupported (we never set TC in responses, so
 * clients have no reason to retry over TCP).
 */
object PacketParser {

    fun parseUDP(packet: ByteArray): ParsedUdpPacket? {
        if (packet.isEmpty()) return null
        return when ((packet[0].toInt() and 0xFF) shr 4) {
            4 -> parseIPv4(packet)
            6 -> parseIPv6(packet)
            else -> null
        }
    }

    private fun parseIPv4(b: ByteArray): ParsedUdpPacket? {
        if (b.size < 20) return null
        val headerLength = (b[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || b.size < headerLength + 8) return null

        // Reject fragments: MF flag or nonzero fragment offset.
        val fragmentField = u16(b, 6)
        if (fragmentField and 0x3FFF != 0) return null

        if ((b[9].toInt() and 0xFF) != 17) return null

        val totalLength = u16(b, 2)
        if (totalLength < headerLength + 8 || b.size < totalLength) return null

        val sourcePort = u16(b, headerLength)
        val destinationPort = u16(b, headerLength + 2)
        val udpLength = u16(b, headerLength + 4)
        if (udpLength < 8 || headerLength + udpLength > totalLength) return null

        return ParsedUdpPacket(
            ipVersion = 4,
            sourceAddress = b.copyOfRange(12, 16),
            destinationAddress = b.copyOfRange(16, 20),
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = b.copyOfRange(headerLength + 8, headerLength + udpLength),
        )
    }

    private fun parseIPv6(b: ByteArray): ParsedUdpPacket? {
        if (b.size < 48) return null

        // Only a bare UDP next-header is accepted; extension-header chains
        // (including fragments, next header 44) are dropped.
        if ((b[6].toInt() and 0xFF) != 17) return null

        val payloadLength = u16(b, 4)
        if (payloadLength < 8 || b.size < 40 + payloadLength) return null

        val sourcePort = u16(b, 40)
        val destinationPort = u16(b, 42)
        val udpLength = u16(b, 44)
        if (udpLength < 8 || udpLength > payloadLength) return null

        return ParsedUdpPacket(
            ipVersion = 6,
            sourceAddress = b.copyOfRange(8, 24),
            destinationAddress = b.copyOfRange(24, 40),
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = b.copyOfRange(48, 40 + udpLength),
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
