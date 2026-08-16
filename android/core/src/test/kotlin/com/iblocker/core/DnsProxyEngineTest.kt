package com.iblocker.core

import com.iblocker.core.dns.DnsRecordType
import com.iblocker.core.engine.DnsProxyEngine
import com.iblocker.core.engine.DnsUpstream
import com.iblocker.core.engine.UpstreamException
import com.iblocker.core.log.LogVerdict
import com.iblocker.core.log.QueryLogRingReader
import com.iblocker.core.log.QueryLogRingWriter
import com.iblocker.core.log.StatsPersistence
import com.iblocker.core.packet.PacketParser
import com.iblocker.core.rules.CompiledBlocklist
import com.iblocker.core.rules.Fnv1a
import com.iblocker.core.rules.SeedRules
import com.iblocker.core.shared.AppPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class MockUpstream(val handler: suspend (ByteArray) -> ByteArray) : DnsUpstream {
    override suspend fun resolve(query: ByteArray): ByteArray = handler(query)
}

/** Echoes the query back with QR set — a structurally valid "answer". */
private fun fakeAnswer(query: ByteArray): ByteArray {
    val bytes = query.copyOf()
    bytes[2] = (bytes[2].toInt() or 0x80).toByte()
    return bytes
}

class DnsProxyEngineTest {

    private lateinit var paths: AppPaths

    @Before
    fun setUp() {
        paths = AppPaths(makeTempDirectory())
        paths.ensureDirectories()

        CompiledBlocklist.write(
            listOf("ads.example.com", "doubleclick.net").map { Fnv1a.hash64(it) },
            1u,
            paths.blocklistFile,
        )
        CompiledBlocklist.write(
            listOf("safe.doubleclick.net").map { Fnv1a.hash64(it) },
            1u,
            paths.userAllowlistFile,
        )
    }

    private fun makeEngine(
        upstream: DnsUpstream,
        configuration: DnsProxyEngine.Configuration = DnsProxyEngine.Configuration(),
    ) = DnsProxyEngine(
        matcher = paths.loadMatcher(),
        upstream = upstream,
        logWriter = QueryLogRingWriter(paths.queryLogFile, capacity = 1024),
        statsFile = paths.statsFile,
        configuration = configuration,
    )

    private fun dnsPayload(reply: ByteArray): MiniDnsResponse {
        val udp = PacketParser.parseUDP(reply)!!
        return MiniDnsResponse.parse(udp.payload)!!
    }

    @Test
    fun blockedDomainGetsZeroAnswerWithoutUpstream() = runTest {
        val engine = makeEngine(MockUpstream { fail("blocked queries must not reach the upstream"); ByteArray(0) })

        val query = makeDnsQueryData(id = 7, name = "ads.example.com")
        val reply = engine.handlePacket(makeUdpPacketV4(payload = query))!!

        val response = dnsPayload(reply)
        assertEquals(7, response.id)
        assertEquals(0, response.rcode)
        assertEquals(DnsRecordType.A, response.answerType)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), response.answerRData)

        // Reply packet goes back to the querying socket.
        val udp = PacketParser.parseUDP(reply)!!
        assertEquals(53, udp.sourcePort)
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), udp.destinationAddress)
    }

    @Test
    fun subdomainOfBlockedDomainIsBlocked() = runTest {
        val engine = makeEngine(MockUpstream { throw UpstreamException.Timeout() })
        val reply = engine.handlePacket(makeUdpPacketV4(payload = makeDnsQueryData(name = "x.tracking.doubleclick.net")))!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), dnsPayload(reply).answerRData)
    }

    @Test
    fun blockedHttpsQueryGetsNoData() = runTest {
        val engine = makeEngine(MockUpstream { throw UpstreamException.Timeout() })
        val query = makeDnsQueryData(name = "ads.example.com", qtype = DnsRecordType.HTTPS)
        val response = dnsPayload(engine.handlePacket(makeUdpPacketV4(payload = query))!!)
        assertEquals(0, response.rcode)
        assertEquals(0, response.ancount)
    }

    @Test
    fun blockedAaaaOverIPv6() = runTest {
        val engine = makeEngine(MockUpstream { throw UpstreamException.Timeout() })
        val query = makeDnsQueryData(name = "ads.example.com", qtype = DnsRecordType.AAAA)
        val reply = engine.handlePacket(makeUdpPacketV6(payload = query))!!
        assertArrayEquals(ByteArray(16), dnsPayload(reply).answerRData)
        assertEquals(6, PacketParser.parseUDP(reply)!!.ipVersion)
    }

    @Test
    fun canaryDomainGetsNxdomain() = runTest {
        // The bundled bypass ruleset covers Firefox's canary; the answer has
        // to be "no such name", not a blackhole address, or DoH stays on.
        CompiledBlocklist.write(
            SeedRules.canaryDomains.map { Fnv1a.hash64(it) },
            2u,
            paths.blocklistFile,
        )
        val engine = makeEngine(MockUpstream { fail("canary must not reach the upstream"); ByteArray(0) })
        val query = makeDnsQueryData(name = "use-application-dns.net")
        val response = dnsPayload(engine.handlePacket(makeUdpPacketV4(payload = query))!!)
        assertEquals(3, response.rcode)
        assertEquals(0, response.ancount)
    }

    @Test
    fun allowedDomainIsForwarded() = runTest {
        val engine = makeEngine(MockUpstream { fakeAnswer(it) })
        val query = makeDnsQueryData(id = 0x77, name = "www.apple.com")
        val response = dnsPayload(engine.handlePacket(makeUdpPacketV4(payload = query))!!)
        assertTrue(response.isResponse)
        assertEquals(0x77, response.id)
        assertEquals("www.apple.com", response.questionName)
    }

    @Test
    fun userAllowBeatsBlocklist() = runTest {
        val engine = makeEngine(MockUpstream { fakeAnswer(it) })
        val query = makeDnsQueryData(name = "cdn.safe.doubleclick.net")
        // Forwarded (fake answer echo), not a 0.0.0.0 synthesis.
        assertNull(dnsPayload(engine.handlePacket(makeUdpPacketV4(payload = query))!!).answerRData)
    }

    @Test
    fun upstreamFailureYieldsServfail() = runTest {
        val engine = makeEngine(MockUpstream { throw UpstreamException.Timeout() })
        val query = makeDnsQueryData(name = "www.apple.com")
        assertEquals(2, dnsPayload(engine.handlePacket(makeUdpPacketV4(payload = query))!!).rcode)
    }

    @Test
    fun oversizeAnswerGetsTruncatedFlag() = runTest {
        val engine = makeEngine(MockUpstream { fakeAnswer(it) + ByteArray(2000) })
        val query = makeDnsQueryData(name = "big.example.com")
        assertTrue(dnsPayload(engine.handlePacket(makeUdpPacketV4(payload = query))!!).isTruncated)
    }

    @Test
    fun nonDnsTrafficIsDropped() = runTest {
        val engine = makeEngine(MockUpstream { throw UpstreamException.Timeout() })

        assertNull(engine.handlePacket(makeUdpPacketV4(destinationPort = 8080, payload = byteArrayOf(1, 2, 3))))

        val tcp = makeUdpPacketV4(payload = makeDnsQueryData(name = "a.example.com"))
        tcp[9] = 6
        assertNull(engine.handlePacket(tcp))

        assertNull(engine.handlePacket(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
    }

    @Test
    fun unparseableDnsIsForwardedRaw() = runTest {
        // A response-shaped message (QR=1) inside a UDP:53 packet: forwarded untouched.
        val responseLike = makeDnsQueryData(name = "weird.example.com")
        responseLike[2] = (responseLike[2].toInt() or 0x80).toByte()

        val engine = makeEngine(
            MockUpstream { query ->
                assertArrayEquals(responseLike, query)
                fakeAnswer(query)
            }
        )
        assertNotNull(engine.handlePacket(makeUdpPacketV4(payload = responseLike)))
    }

    @Test
    fun statsAndLogAreRecorded() = runTest {
        val engine = makeEngine(MockUpstream { fakeAnswer(it) })

        engine.handlePacket(makeUdpPacketV4(payload = makeDnsQueryData(name = "ads.example.com")))
        engine.handlePacket(makeUdpPacketV4(payload = makeDnsQueryData(name = "www.apple.com")))
        engine.handlePacket(makeUdpPacketV4(payload = makeDnsQueryData(name = "doubleclick.net")))
        engine.flush()

        val stats = engine.statsSnapshot()
        assertEquals(3L, stats.totalQueries)
        assertEquals(2L, stats.blockedQueries)
        assertEquals(2L, stats.blocklistEntryCount)
        assertNotNull(stats.startedAtMillis)

        val persisted = StatsPersistence.load(paths.statsFile)
        assertEquals(3L, persisted.totalQueries)
        assertEquals(2L, persisted.totalBlocked)

        val records = QueryLogRingReader(paths.queryLogFile).read(since = 0).records
        assertEquals(3, records.size)
        assertEquals(
            listOf("ads.example.com", "doubleclick.net"),
            records.filter { it.verdict == LogVerdict.BLOCKED }.map { it.domain }.sorted(),
        )
    }

    @Test
    fun pauseForwardsBlockedDomains() = runTest {
        val engine = makeEngine(MockUpstream { fakeAnswer(it) })
        val packet = makeUdpPacketV4(payload = makeDnsQueryData(name = "ads.example.com"))

        // Blocked normally.
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), dnsPayload(engine.handlePacket(packet)!!).answerRData)

        // Paused -> forwarded (fake upstream echo, no synthesized answer).
        engine.setPaused(System.currentTimeMillis() + 300_000)
        val during = dnsPayload(engine.handlePacket(packet)!!)
        assertNull(during.answerRData)
        assertTrue(during.isResponse)
        assertNotNull(engine.statsSnapshot().pausedUntilMillis)

        // A past deadline is treated as not paused.
        engine.setPaused(System.currentTimeMillis() - 1000)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), dnsPayload(engine.handlePacket(packet)!!).answerRData)
        assertNull(engine.statsSnapshot().pausedUntilMillis)

        // Explicit resume.
        engine.setPaused(System.currentTimeMillis() + 300_000)
        engine.setPaused(null)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), dnsPayload(engine.handlePacket(packet)!!).answerRData)
    }

    @Test
    fun reloadPicksUpNewRules() = runTest {
        val engine = makeEngine(MockUpstream { fakeAnswer(it) })

        val name = "fresh.example.org"
        val packet = makeUdpPacketV4(payload = makeDnsQueryData(name = name))
        assertNull(dnsPayload(engine.handlePacket(packet)!!).answerRData) // forwarded

        CompiledBlocklist.write(listOf(Fnv1a.hash64(name)), 2u, paths.blocklistFile)
        engine.reload(paths.loadMatcher())
        assertEquals(2u, engine.blocklistGeneration())

        // Now blocked.
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), dnsPayload(engine.handlePacket(packet)!!).answerRData)
    }

    @Test
    fun inFlightLimitYieldsServfail() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = makeEngine(
            MockUpstream { query ->
                gate.await()
                fakeAnswer(query)
            },
            DnsProxyEngine.Configuration(maxInFlight = 1),
        )

        val first = async {
            engine.handlePacket(makeUdpPacketV4(payload = makeDnsQueryData(id = 1, name = "one.example.com")))
        }
        // Let the first query reach the (blocked) upstream.
        repeat(5) { yield() }

        val second = engine.handlePacket(makeUdpPacketV4(payload = makeDnsQueryData(id = 2, name = "two.example.com")))!!
        assertEquals(2, dnsPayload(second).rcode)

        gate.complete(Unit)
        assertNotNull(first.await())
    }
}
