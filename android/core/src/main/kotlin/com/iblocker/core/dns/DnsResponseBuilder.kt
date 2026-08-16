package com.iblocker.core.dns

/**
 * Synthesizes DNS responses locally, without touching the network.
 *
 * Responses echo the question section byte-exactly (preserving 0x20 case
 * randomization) and never set TC, so clients never retry over TCP.
 * EDNS0 is deliberately stripped: synthesized responses are tiny.
 */
object DnsResponseBuilder {

    /**
     * The response for a blocked name.
     *
     * A queries get 0.0.0.0, AAAA queries get ::, and every other qtype
     * (crucially HTTPS/SVCB type 65/64, which carry address hints that would
     * otherwise leak past a naive A/AAAA-only blocker) gets NOERROR/NODATA.
     */
    fun blocked(query: DnsQuery, ttl: Int = 300): ByteArray = when (query.qtype) {
        DnsRecordType.A -> build(query, rcode = 0, answerRData = byteArrayOf(0, 0, 0, 0), ttl = ttl)
        DnsRecordType.AAAA -> build(query, rcode = 0, answerRData = ByteArray(16), ttl = ttl)
        else -> build(query, rcode = 0, answerRData = null, ttl = ttl)
    }

    fun nxdomain(query: DnsQuery): ByteArray = build(query, rcode = 3, answerRData = null, ttl = 0)

    fun servfail(query: DnsQuery): ByteArray = build(query, rcode = 2, answerRData = null, ttl = 0)

    /**
     * Header-only response with TC set, for the rare upstream answer too
     * large to fit back through the tunnel MTU unfragmented.
     */
    fun truncated(query: DnsQuery): ByteArray =
        build(query, rcode = 0, answerRData = null, ttl = 0, extraFlags = 0x0200)

    private fun build(
        query: DnsQuery,
        rcode: Int,
        answerRData: ByteArray?,
        ttl: Int,
        extraFlags: Int = 0,
    ): ByteArray {
        val out = ArrayList<Byte>(12 + query.rawQuestion.size + 16 + (answerRData?.size ?: 0))

        // Header: QR=1, opcode=0, AA=0, TC=0, RD echoed, RA=1, Z=0.
        var flags = 0x8000 or 0x0080 or (rcode and 0x0F) or extraFlags
        flags = flags or (query.flags and 0x0100)

        appendU16(out, query.transactionID)
        appendU16(out, flags)
        appendU16(out, 1)                                   // QDCOUNT
        appendU16(out, if (answerRData == null) 0 else 1)   // ANCOUNT
        appendU16(out, 0)                                   // NSCOUNT
        appendU16(out, 0)                                   // ARCOUNT

        query.rawQuestion.forEach { out.add(it) }

        if (answerRData != null) {
            out.add(0xC0.toByte())                          // pointer to question name at offset 12
            out.add(0x0C)
            appendU16(out, query.qtype)
            appendU16(out, query.qclass)
            out.add(((ttl shr 24) and 0xFF).toByte())
            out.add(((ttl shr 16) and 0xFF).toByte())
            out.add(((ttl shr 8) and 0xFF).toByte())
            out.add((ttl and 0xFF).toByte())
            appendU16(out, answerRData.size)
            answerRData.forEach { out.add(it) }
        }

        return out.toByteArray()
    }

    private fun appendU16(out: MutableList<Byte>, value: Int) {
        out.add(((value shr 8) and 0xFF).toByte())
        out.add((value and 0xFF).toByte())
    }
}
