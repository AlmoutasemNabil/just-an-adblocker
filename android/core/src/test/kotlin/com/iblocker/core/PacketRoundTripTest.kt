package com.iblocker.core

import com.iblocker.core.dns.DnsRecordType
import com.iblocker.core.packet.PacketParser
import com.iblocker.core.packet.UdpReplyBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketRoundTripTest {

    @Test
    fun parseIPv4Udp() {
        val payload = makeDnsQueryData(name = "example.com")
        val packet = makeUdpPacketV4(sourcePort = 50000, payload = payload)

        val parsed = PacketParser.parseUDP(packet)!!
        assertEquals(4, parsed.ipVersion)
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), parsed.sourceAddress)
        assertArrayEquals(byteArrayOf(198.toByte(), 18, 0, 2), parsed.destinationAddress)
        assertEquals(50000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun parseIPv6Udp() {
        val payload = makeDnsQueryData(name = "example.com", qtype = DnsRecordType.AAAA)
        val packet = makeUdpPacketV6(payload = payload)

        val parsed = PacketParser.parseUDP(packet)!!
        assertEquals(6, parsed.ipVersion)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload)
        assertEquals(16, parsed.destinationAddress.size)
    }

    @Test
    fun rejectsFragmentsTcpAndGarbage() {
        val payload = makeDnsQueryData(name = "example.com")

        val fragmented = makeUdpPacketV4(payload = payload)
        fragmented[6] = 0x20 // MF flag
        assertNull(PacketParser.parseUDP(fragmented))

        val offsetFragment = makeUdpPacketV4(payload = payload)
        offsetFragment[6] = 0x00
        offsetFragment[7] = 0x08 // fragment offset 8
        assertNull(PacketParser.parseUDP(offsetFragment))

        val tcp = makeUdpPacketV4(payload = payload)
        tcp[9] = 6
        assertNull(PacketParser.parseUDP(tcp))

        assertNull(PacketParser.parseUDP(byteArrayOf(0x45, 0x00)))
        assertNull(PacketParser.parseUDP(ByteArray(0)))
        assertNull(PacketParser.parseUDP(byteArrayOf(0x10, 0x00, 0x00)))
    }

    @Test
    fun ipv4ReplySwapsTupleAndChecksums() {
        val payload = makeDnsQueryData(name = "example.com")
        val request = PacketParser.parseUDP(makeUdpPacketV4(sourcePort = 43210, payload = payload))!!

        val responsePayload = "response".toByteArray()
        val reply = UdpReplyBuilder.reply(request, responsePayload)!!

        // The reply must itself parse, with the tuple reversed.
        val parsedReply = PacketParser.parseUDP(reply)!!
        assertArrayEquals(byteArrayOf(198.toByte(), 18, 0, 2), parsedReply.sourceAddress)
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), parsedReply.destinationAddress)
        assertEquals(53, parsedReply.sourcePort)
        assertEquals(43210, parsedReply.destinationPort)
        assertArrayEquals(responsePayload, parsedReply.payload)

        // Checksums must be valid.
        assertTrue(ipv4HeaderChecksumIsValid(reply.copyOfRange(0, 20)))
        assertTrue(
            udpChecksumIsValid(
                4,
                parsedReply.sourceAddress,
                parsedReply.destinationAddress,
                reply.copyOfRange(20, reply.size),
            )
        )
    }

    @Test
    fun ipv6ReplySwapsTupleAndChecksums() {
        val payload = makeDnsQueryData(name = "example.com", qtype = DnsRecordType.AAAA)
        val request = PacketParser.parseUDP(makeUdpPacketV6(sourcePort = 44444, payload = payload))!!

        val responsePayload = "v6-response".toByteArray()
        val reply = UdpReplyBuilder.reply(request, responsePayload)
        assertNotNull(reply)

        val parsedReply = PacketParser.parseUDP(reply!!)!!
        assertEquals(6, parsedReply.ipVersion)
        assertEquals(53, parsedReply.sourcePort)
        assertEquals(44444, parsedReply.destinationPort)
        assertArrayEquals(responsePayload, parsedReply.payload)
        assertArrayEquals(request.destinationAddress, parsedReply.sourceAddress)
        assertArrayEquals(request.sourceAddress, parsedReply.destinationAddress)

        assertTrue(
            udpChecksumIsValid(
                6,
                parsedReply.sourceAddress,
                parsedReply.destinationAddress,
                reply.copyOfRange(40, reply.size),
            )
        )
    }
}
