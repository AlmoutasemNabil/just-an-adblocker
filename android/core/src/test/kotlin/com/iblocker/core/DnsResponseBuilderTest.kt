package com.iblocker.core

import com.iblocker.core.dns.DnsParser
import com.iblocker.core.dns.DnsQuery
import com.iblocker.core.dns.DnsRecordType
import com.iblocker.core.dns.DnsResponseBuilder
import com.iblocker.core.dns.DnsWireException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DnsResponseBuilderTest {

    private fun parseQuery(name: String, qtype: Int, id: Int = 0x4242): DnsQuery =
        DnsParser.parseQuery(makeDnsQueryData(id = id, name = name, qtype = qtype))

    @Test
    fun blockedAReturnsZeroAddress() {
        val query = parseQuery("ads.example.com", DnsRecordType.A)
        val response = MiniDnsResponse.parse(DnsResponseBuilder.blocked(query))!!

        assertEquals(0x4242, response.id)
        assertTrue(response.isResponse)
        assertTrue(response.recursionAvailable)
        assertFalse(response.isTruncated)
        assertEquals(0, response.rcode)
        assertEquals(1, response.qdcount)
        assertEquals(1, response.ancount)
        assertEquals("ads.example.com", response.questionName)
        assertEquals(DnsRecordType.A, response.answerType)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), response.answerRData)
    }

    @Test
    fun blockedAaaaReturnsZeroAddress() {
        val query = parseQuery("ads.example.com", DnsRecordType.AAAA)
        val response = MiniDnsResponse.parse(DnsResponseBuilder.blocked(query))!!
        assertEquals(1, response.ancount)
        assertEquals(DnsRecordType.AAAA, response.answerType)
        assertArrayEquals(ByteArray(16), response.answerRData)
    }

    @Test
    fun blockedHttpsTypeReturnsNoData() {
        // HTTPS (type 65) records carry address hints; answering NODATA
        // instead of ignoring them is what keeps modern browsers from leaking.
        val query = parseQuery("ads.example.com", DnsRecordType.HTTPS)
        val response = MiniDnsResponse.parse(DnsResponseBuilder.blocked(query))!!
        assertEquals(0, response.rcode)
        assertEquals(0, response.ancount)
    }

    @Test
    fun nxdomainAndServfailAndTruncated() {
        val query = parseQuery("x.example.com", DnsRecordType.A)
        assertEquals(3, MiniDnsResponse.parse(DnsResponseBuilder.nxdomain(query))!!.rcode)
        assertEquals(2, MiniDnsResponse.parse(DnsResponseBuilder.servfail(query))!!.rcode)
        val truncated = MiniDnsResponse.parse(DnsResponseBuilder.truncated(query))!!
        assertTrue(truncated.isTruncated)
        assertEquals(0, truncated.ancount)
    }

    @Test
    fun questionEchoIsByteExact() {
        val data = makeDnsQueryData(id = 1, name = "AdS.ExAmPle.CoM", qtype = DnsRecordType.A)
        val query = DnsParser.parseQuery(data)
        val response = DnsResponseBuilder.blocked(query)
        assertArrayEquals(query.rawQuestion, response.copyOfRange(12, 12 + query.rawQuestion.size))
    }

    @Test
    fun recursionDesiredIsEchoed() {
        val noRD = DnsParser.parseQuery(makeDnsQueryData(name = "a.example.com", recursionDesired = false))
        val response = MiniDnsResponse.parse(DnsResponseBuilder.blocked(noRD))!!
        assertEquals(0, response.flags and 0x0100)
    }

    @Test
    fun responseParsesWithOwnParserAsResponse() {
        // Our own parser must reject what we emit (QR=1) — guards loops.
        val query = parseQuery("ads.example.com", DnsRecordType.A)
        val blocked = DnsResponseBuilder.blocked(query)
        assertNotNull(MiniDnsResponse.parse(blocked))
        try {
            DnsParser.parseQuery(blocked)
            fail("a synthesized response must not parse as a query")
        } catch (_: DnsWireException) {
            // expected
        }
    }
}
