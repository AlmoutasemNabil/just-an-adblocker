package com.iblocker.core

import com.iblocker.core.packet.InternetChecksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumTest {

    /**
     * The classic worked example (appears in countless texts): IPv4 header
     * whose correct checksum is 0xB1E6.
     */
    @Test
    fun ipv4HeaderKnownVector() {
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x3C, 0x1C, 0x46, 0x40, 0x00,
            0x40, 0x06, 0x00, 0x00, 0xAC.toByte(), 0x10, 0x0A, 0x63,
            0xAC.toByte(), 0x10, 0x0A, 0x0C,
        )
        assertEquals(0xB1E6, InternetChecksum.ipv4Header(header))

        val complete = header.copyOf()
        complete[10] = 0xB1.toByte()
        complete[11] = 0xE6.toByte()
        assertTrue(ipv4HeaderChecksumIsValid(complete))
    }

    @Test
    fun oddLengthPadding() {
        // Odd byte is padded on the right: [0x01] sums as 0x0100.
        assertEquals(0x0100L, InternetChecksum.sum16(byteArrayOf(0x01)))
        assertEquals((0x0001 + 0x0200).toLong(), InternetChecksum.sum16(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun carryFolding() {
        // 0xFFFF + 0xFFFF requires folding twice.
        assertEquals(0x0000, InternetChecksum.finalize(0x1FFFE))
        assertEquals(0xFFFF, InternetChecksum.finalize(0))
    }

    @Test
    fun udpV4ChecksumValidatesRoundTrip() {
        val src = byteArrayOf(192.toByte(), 168.toByte(), 1, 10)
        val dst = byteArrayOf(198.toByte(), 18, 0, 2)
        val segment = byteArrayOf(0xD9.toByte(), 0x03, 0x00, 0x35, 0x00, 0x0D, 0x00, 0x00) + "hello".toByteArray()

        val checksum = InternetChecksum.udp(4, src, dst, segment)
        assertNotEquals(0, checksum)
        segment[6] = ((checksum shr 8) and 0xFF).toByte()
        segment[7] = (checksum and 0xFF).toByte()
        assertTrue(udpChecksumIsValid(4, src, dst, segment))
    }

    @Test
    fun udpV6ChecksumValidatesRoundTrip() {
        val src = ByteArray(16) { 0xFD.toByte() }
        val dst = ByteArray(16).also { it[15] = 2 }
        val segment = byteArrayOf(0xD9.toByte(), 0x04, 0x00, 0x35, 0x00, 0x0C, 0x00, 0x00) + "ipv6".toByteArray()

        val checksum = InternetChecksum.udp(6, src, dst, segment)
        segment[6] = ((checksum shr 8) and 0xFF).toByte()
        segment[7] = (checksum and 0xFF).toByte()
        assertTrue(udpChecksumIsValid(6, src, dst, segment))
    }

    @Test
    fun zeroChecksumTransmitsAsFFFF() {
        // Craft a segment whose ones-complement sum is 0xFFFF so the computed
        // checksum would be 0x0000: RFC 768 requires transmitting 0xFFFF.
        val src = byteArrayOf(0, 0, 0, 0)
        val dst = byteArrayOf(0, 0, 0, 0)
        val segment = byteArrayOf(0, 0, 0, 0, 0, 10, 0, 0, 0, 0)

        var sum = 17L + 10L
        sum = InternetChecksum.sum16(segment, sum)
        var folded = sum
        while (folded > 0xFFFF) folded = (folded and 0xFFFF) + (folded shr 16)
        val needed = (0xFFFF - folded).toInt()
        segment[8] = ((needed shr 8) and 0xFF).toByte()
        segment[9] = (needed and 0xFF).toByte()

        assertEquals(0xFFFF, InternetChecksum.udp(4, src, dst, segment))
    }
}
