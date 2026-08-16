package com.iblocker.core

import com.iblocker.core.dns.DnsRecordType
import com.iblocker.core.packet.InternetChecksum
import java.io.File
import java.nio.file.Files
import java.util.UUID

// MARK: - DNS message construction

fun makeDnsQueryData(
    id: Int = 0x1234,
    name: String,
    qtype: Int = DnsRecordType.A,
    recursionDesired: Boolean = true,
    edns: Boolean = false,
): ByteArray {
    val out = ArrayList<Byte>()
    out.add((id shr 8).toByte()); out.add((id and 0xFF).toByte())
    val flags = if (recursionDesired) 0x0100 else 0
    out.add((flags shr 8).toByte()); out.add((flags and 0xFF).toByte())
    out.addAll(listOf<Byte>(0, 1))                                  // QDCOUNT
    out.addAll(listOf<Byte>(0, 0, 0, 0))                            // ANCOUNT, NSCOUNT
    out.addAll(if (edns) listOf<Byte>(0, 1) else listOf<Byte>(0, 0)) // ARCOUNT

    for (label in name.split(".")) {
        val bytes = label.toByteArray(Charsets.UTF_8)
        out.add(bytes.size.toByte())
        bytes.forEach { out.add(it) }
    }
    out.add(0)
    out.add((qtype shr 8).toByte()); out.add((qtype and 0xFF).toByte())
    out.addAll(listOf<Byte>(0, 1))                                  // IN

    if (edns) {
        out.add(0)                                                  // root name
        out.addAll(listOf<Byte>(0, 41))                             // OPT
        out.addAll(listOf(0x10.toByte(), 0x00.toByte()))            // 4096 payload size
        out.addAll(listOf<Byte>(0, 0, 0, 0))                        // extended flags
        out.addAll(listOf<Byte>(0, 0))                              // RDLEN
    }
    return out.toByteArray()
}

// MARK: - Minimal response reader (independent of production code paths)

class MiniDnsResponse private constructor(
    val id: Int,
    val flags: Int,
    val qdcount: Int,
    val ancount: Int,
    val questionName: String,
    val answerType: Int?,
    val answerRData: ByteArray?,
) {
    val rcode: Int get() = flags and 0x0F
    val isResponse: Boolean get() = flags and 0x8000 != 0
    val isTruncated: Boolean get() = flags and 0x0200 != 0
    val recursionAvailable: Boolean get() = flags and 0x0080 != 0

    companion object {
        fun parse(data: ByteArray): MiniDnsResponse? {
            if (data.size < 12) return null
            fun u16(offset: Int) = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

            val ancount = u16(6)
            var i = 12
            val labels = ArrayList<String>()
            while (i < data.size && data[i].toInt() != 0) {
                val len = data[i].toInt() and 0xFF
                if (len and 0xC0 != 0 || i + 1 + len > data.size) return null
                labels.add(String(data, i + 1, len, Charsets.UTF_8).lowercase())
                i += len + 1
            }
            if (i + 5 > data.size) return null
            i += 5 // zero label + qtype + qclass

            var answerType: Int? = null
            var answerRData: ByteArray? = null
            if (ancount >= 1) {
                if (i + 12 > data.size ||
                    (data[i].toInt() and 0xFF) != 0xC0 ||
                    (data[i + 1].toInt() and 0xFF) != 0x0C
                ) {
                    return null
                }
                val rdlen = ((data[i + 10].toInt() and 0xFF) shl 8) or (data[i + 11].toInt() and 0xFF)
                if (i + 12 + rdlen > data.size) return null
                answerType = u16(i + 2)
                answerRData = data.copyOfRange(i + 12, i + 12 + rdlen)
            }

            return MiniDnsResponse(
                id = u16(0),
                flags = u16(2),
                qdcount = u16(4),
                ancount = ancount,
                questionName = labels.joinToString("."),
                answerType = answerType,
                answerRData = answerRData,
            )
        }
    }
}

// MARK: - Raw packet construction

fun makeUdpPacketV4(
    source: ByteArray = byteArrayOf(10, 0, 0, 5),
    destination: ByteArray = byteArrayOf(198.toByte(), 18, 0, 2),
    sourcePort: Int = 55555,
    destinationPort: Int = 53,
    payload: ByteArray,
): ByteArray {
    val udpLength = 8 + payload.size
    val totalLength = 20 + udpLength

    val header = ByteArray(20)
    header[0] = 0x45
    header[2] = ((totalLength shr 8) and 0xFF).toByte()
    header[3] = (totalLength and 0xFF).toByte()
    header[4] = 0xAB.toByte()
    header[5] = 0xCD.toByte()
    header[6] = 0x40
    header[8] = 64
    header[9] = 17
    source.copyInto(header, 12)
    destination.copyInto(header, 16)
    val hc = InternetChecksum.ipv4Header(header)
    header[10] = ((hc shr 8) and 0xFF).toByte()
    header[11] = (hc and 0xFF).toByte()

    val segment = ByteArray(udpLength)
    segment[0] = ((sourcePort shr 8) and 0xFF).toByte()
    segment[1] = (sourcePort and 0xFF).toByte()
    segment[2] = ((destinationPort shr 8) and 0xFF).toByte()
    segment[3] = (destinationPort and 0xFF).toByte()
    segment[4] = ((udpLength shr 8) and 0xFF).toByte()
    segment[5] = (udpLength and 0xFF).toByte()
    payload.copyInto(segment, 8)
    val uc = InternetChecksum.udp(4, source, destination, segment)
    segment[6] = ((uc shr 8) and 0xFF).toByte()
    segment[7] = (uc and 0xFF).toByte()

    return header + segment
}

fun makeUdpPacketV6(
    source: ByteArray? = null,
    destination: ByteArray? = null,
    sourcePort: Int = 55556,
    destinationPort: Int = 53,
    payload: ByteArray,
): ByteArray {
    val src = source ?: ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 0x10 }
    val dst = destination ?: ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 0x02 }
    val udpLength = 8 + payload.size

    val header = ByteArray(40)
    header[0] = 0x60
    header[4] = ((udpLength shr 8) and 0xFF).toByte()
    header[5] = (udpLength and 0xFF).toByte()
    header[6] = 17
    header[7] = 64
    src.copyInto(header, 8)
    dst.copyInto(header, 24)

    val segment = ByteArray(udpLength)
    segment[0] = ((sourcePort shr 8) and 0xFF).toByte()
    segment[1] = (sourcePort and 0xFF).toByte()
    segment[2] = ((destinationPort shr 8) and 0xFF).toByte()
    segment[3] = (destinationPort and 0xFF).toByte()
    segment[4] = ((udpLength shr 8) and 0xFF).toByte()
    segment[5] = (udpLength and 0xFF).toByte()
    payload.copyInto(segment, 8)
    val uc = InternetChecksum.udp(6, src, dst, segment)
    segment[6] = ((uc shr 8) and 0xFF).toByte()
    segment[7] = (uc and 0xFF).toByte()

    return header + segment
}

// MARK: - Checksum verification (a checksummed span folds to 0)

fun ipv4HeaderChecksumIsValid(header: ByteArray): Boolean =
    InternetChecksum.finalize(InternetChecksum.sum16(header)) == 0

fun udpChecksumIsValid(ipVersion: Int, source: ByteArray, destination: ByteArray, segment: ByteArray): Boolean {
    var sum = 0L
    sum = InternetChecksum.sum16(source, sum)
    sum = InternetChecksum.sum16(destination, sum)
    val length = segment.size.toLong()
    if (ipVersion == 4) {
        sum += 17
        sum += length and 0xFFFF
    } else {
        sum += (length shr 16) and 0xFFFF
        sum += length and 0xFFFF
        sum += 17
    }
    sum = InternetChecksum.sum16(segment, sum)
    return InternetChecksum.finalize(sum) == 0
}

// MARK: - Misc

fun makeTempDirectory(): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "iblocker-tests-${UUID.randomUUID()}")
    Files.createDirectories(dir.toPath())
    dir.deleteOnExit()
    return dir
}

/** Deterministic pseudo-random generator for property tests (xorshift64). */
class SeededGenerator(seed: ULong) {
    private var state: ULong = if (seed == 0uL) 0x9E3779B97F4A7C15uL else seed

    fun next(): ULong {
        state = state xor (state shl 13)
        state = state xor (state shr 7)
        state = state xor (state shl 17)
        return state
    }
}
