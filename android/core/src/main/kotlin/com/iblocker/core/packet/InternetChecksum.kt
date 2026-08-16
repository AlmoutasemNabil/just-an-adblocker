package com.iblocker.core.packet

/** RFC 1071 internet checksum, plus the IPv4/IPv6 UDP pseudo-header variants. */
object InternetChecksum {

    /**
     * Running 16-bit ones-complement sum of [bytes], big-endian pairs.
     * An odd trailing byte is padded with zero on the right.
     */
    fun sum16(bytes: ByteArray, initial: Long = 0): Long {
        var s = initial
        var i = 0
        val end = bytes.size
        while (i + 1 < end) {
            s += ((bytes[i].toLong() and 0xFF) shl 8) or (bytes[i + 1].toLong() and 0xFF)
            i += 2
        }
        if (i < end) {
            s += (bytes[i].toLong() and 0xFF) shl 8
        }
        return s and 0xFFFFFFFFL
    }

    /** Folds carries and returns the ones-complement of the sum. */
    fun finalize(sum: Long): Int {
        var s = sum and 0xFFFFFFFFL
        while (s > 0xFFFF) {
            s = (s and 0xFFFF) + (s ushr 16)
        }
        return (s.toInt().inv()) and 0xFFFF
    }

    /** Checksum for an IPv4 header whose checksum field bytes are zeroed. */
    fun ipv4Header(header: ByteArray): Int = finalize(sum16(header))

    /**
     * UDP checksum over the pseudo-header and the full UDP segment
     * (header with zeroed checksum field + payload).
     *
     * Per RFC 768 a computed checksum of 0x0000 is transmitted as 0xFFFF.
     * The checksum is optional for IPv4 but mandatory for IPv6, so we always
     * compute it.
     */
    fun udp(ipVersion: Int, source: ByteArray, destination: ByteArray, segment: ByteArray): Int {
        var s = 0L
        s = sum16(source, s)
        s = sum16(destination, s)
        val length = segment.size.toLong()
        if (ipVersion == 4) {
            // zero + protocol, then 16-bit UDP length
            s += 17
            s += length and 0xFFFF
        } else {
            // 32-bit upper-layer length, then 3 zero bytes + next header
            s += (length shr 16) and 0xFFFF
            s += length and 0xFFFF
            s += 17
        }
        s = sum16(segment, s)
        val c = finalize(s)
        return if (c == 0) 0xFFFF else c
    }
}
