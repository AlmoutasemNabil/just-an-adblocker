package com.iblocker.core

import com.iblocker.core.dns.DnsNameCodec
import com.iblocker.core.dns.DnsParser
import com.iblocker.core.dns.DnsRecordType
import com.iblocker.core.dns.DnsWireError
import com.iblocker.core.dns.DnsWireException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DnsMessageTest {

    @Test
    fun parsesSimpleAQuery() {
        val data = makeDnsQueryData(id = 0xBEEF, name = "www.Example.COM", qtype = DnsRecordType.A)
        val query = DnsParser.parseQuery(data)
        assertEquals(0xBEEF, query.transactionID)
        assertEquals("www.example.com", query.questionName)
        assertEquals(DnsRecordType.A, query.qtype)
        assertEquals(1, query.qclass)
        assertTrue(query.recursionDesired)
        assertFalse(query.hasAdditionalRecords)
        assertArrayEquals(data, query.raw)
    }

    @Test
    fun rawQuestionPreservesCase() {
        val data = makeDnsQueryData(name = "wWw.ExAmPlE.cOm")
        val query = DnsParser.parseQuery(data)
        // 0x20 randomization must survive for the response echo.
        assertArrayEquals(data.copyOfRange(12, data.size), query.rawQuestion)
        assertEquals("www.example.com", query.questionName)
    }

    @Test
    fun detectsEdns() {
        val query = DnsParser.parseQuery(makeDnsQueryData(name = "example.com", edns = true))
        assertTrue(query.hasAdditionalRecords)
    }

    @Test
    fun rejectsResponses() {
        val bytes = makeDnsQueryData(name = "example.com")
        bytes[2] = (bytes[2].toInt() or 0x80).toByte()
        try {
            DnsParser.parseQuery(bytes)
            fail("expected a wire error")
        } catch (error: DnsWireException) {
            assertEquals(DnsWireError.NOT_A_QUERY, error.error)
        }
    }

    @Test
    fun rejectsMultiQuestion() {
        val bytes = makeDnsQueryData(name = "example.com")
        bytes[5] = 2
        try {
            DnsParser.parseQuery(bytes)
            fail("expected a wire error")
        } catch (error: DnsWireException) {
            assertEquals(DnsWireError.UNSUPPORTED_QUESTION_COUNT, error.error)
        }
    }

    @Test
    fun rejectsTruncatedMessages() {
        val data = makeDnsQueryData(name = "example.com")
        assertThrowsWireError { DnsParser.parseQuery(data.copyOfRange(0, 8)) }
        assertThrowsWireError { DnsParser.parseQuery(data.copyOfRange(0, 14)) }
    }

    @Test
    fun nameDecodeFollowsCompressionPointers() {
        val bytes = ArrayList<Byte>()
        repeat(12) { bytes.add(0) }
        bytes.add(3); "www".toByteArray().forEach { bytes.add(it) }
        bytes.add(7); "EXAMPLE".toByteArray().forEach { bytes.add(it) }
        bytes.add(3); "com".toByteArray().forEach { bytes.add(it) }
        bytes.add(0)
        val pointerOffset = bytes.size
        bytes.add(0xC0.toByte()); bytes.add(0x0C)

        val viaPointer = DnsNameCodec.decode(bytes.toByteArray(), pointerOffset)
        assertEquals("www.example.com", viaPointer.name)
        assertEquals(2, viaPointer.wireLength)

        val direct = DnsNameCodec.decode(bytes.toByteArray(), 12)
        assertEquals("www.example.com", direct.name)
        assertEquals(17, direct.wireLength)
    }

    @Test
    fun nameDecodeRejectsForwardPointerLoops() {
        val bytes = ByteArray(14)
        bytes[12] = 0xC0.toByte()
        bytes[13] = 12 // points at itself
        assertThrowsWireError { DnsNameCodec.decode(bytes, 12) }
    }

    @Test
    fun nameEncodeRoundTrip() {
        val encoded = DnsNameCodec.encode("ads.tracker.example.org")
        assertNotNull(encoded)
        val decoded = DnsNameCodec.decode(encoded!!, 0)
        assertEquals("ads.tracker.example.org", decoded.name)
        assertEquals(encoded.size, decoded.wireLength)
        assertNull(DnsNameCodec.encode("bad..label"))
    }

    private fun assertThrowsWireError(block: () -> Unit) {
        try {
            block()
            fail("expected a wire error")
        } catch (_: DnsWireException) {
            // expected
        }
    }
}
