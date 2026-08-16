package com.iblocker.core.dns

/** Well-known DNS record types the app cares about (display + blocking policy). */
object DnsRecordType {
    const val A = 1
    const val NS = 2
    const val CNAME = 5
    const val SOA = 6
    const val PTR = 12
    const val MX = 15
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33
    const val OPT = 41
    const val SVCB = 64
    const val HTTPS = 65

    fun name(qtype: Int): String = when (qtype) {
        A -> "A"
        NS -> "NS"
        CNAME -> "CNAME"
        SOA -> "SOA"
        PTR -> "PTR"
        MX -> "MX"
        TXT -> "TXT"
        AAAA -> "AAAA"
        SRV -> "SRV"
        OPT -> "OPT"
        SVCB -> "SVCB"
        HTTPS -> "HTTPS"
        else -> "TYPE$qtype"
    }
}

/** A parsed DNS query (QR=0, one question). */
class DnsQuery(
    val transactionID: Int,
    val flags: Int,
    /** Lowercased dotted question name, no trailing dot. */
    val questionName: String,
    /**
     * Byte-exact question section (name + qtype + qclass) as it appeared on
     * the wire, preserving the original case (DNS 0x20 randomization).
     */
    val rawQuestion: ByteArray,
    val qtype: Int,
    val qclass: Int,
    /** True when the query carried anything in the additional section (in practice: EDNS0 OPT). */
    val hasAdditionalRecords: Boolean,
    /** The complete original message, for untouched upstream forwarding. */
    val raw: ByteArray,
) {
    val recursionDesired: Boolean get() = flags and 0x0100 != 0
}

object DnsParser {

    /**
     * Parses a standard query with exactly one question.
     * Throws for responses and multi-question messages; callers should
     * forward those upstream untouched rather than fail the lookup.
     */
    @Throws(DnsWireException::class)
    fun parseQuery(data: ByteArray): DnsQuery {
        if (data.size < 12) throw DnsWireException(DnsWireError.TRUNCATED)

        val id = u16(data, 0)
        val flags = u16(data, 2)
        if (flags and 0x8000 != 0) throw DnsWireException(DnsWireError.NOT_A_QUERY)

        val qdcount = u16(data, 4)
        val arcount = u16(data, 10)
        if (qdcount != 1) throw DnsWireException(DnsWireError.UNSUPPORTED_QUESTION_COUNT)

        val (name, nameLength) = DnsNameCodec.decode(data, 12)
        val qtypeOffset = 12 + nameLength
        if (qtypeOffset + 4 > data.size) throw DnsWireException(DnsWireError.TRUNCATED)

        return DnsQuery(
            transactionID = id,
            flags = flags,
            questionName = name,
            rawQuestion = data.copyOfRange(12, qtypeOffset + 4),
            qtype = u16(data, qtypeOffset),
            qclass = u16(data, qtypeOffset + 2),
            hasAdditionalRecords = arcount > 0,
            raw = data,
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
