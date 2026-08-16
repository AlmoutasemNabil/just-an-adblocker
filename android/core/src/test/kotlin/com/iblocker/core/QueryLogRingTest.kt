package com.iblocker.core

import com.iblocker.core.dns.DnsRecordType
import com.iblocker.core.log.BlockerStats
import com.iblocker.core.log.DayCounters
import com.iblocker.core.log.LogVerdict
import com.iblocker.core.log.QueryLogRecord
import com.iblocker.core.log.QueryLogRing
import com.iblocker.core.log.QueryLogRingReader
import com.iblocker.core.log.QueryLogRingWriter
import com.iblocker.core.log.StatsPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class QueryLogRingTest {

    private fun record(i: Int, domain: String = "example.com") = QueryLogRecord(
        timestampMillis = 1_700_000_000_000L + i,
        qtype = DnsRecordType.A,
        verdict = if (i % 3 == 0) LogVerdict.BLOCKED else LogVerdict.ALLOWED,
        domain = "q$i.$domain",
    )

    @Test
    fun appendFlushRead() {
        val file = File(makeTempDirectory(), "log.ring")
        val writer = QueryLogRingWriter(file, capacity = 1024, flushThreshold = 64)

        for (i in 0 until 100) writer.append(record(i))
        writer.flush()

        val reader = QueryLogRingReader(file)
        val first = reader.read(since = 0)
        assertEquals(100L, first.cursor)
        assertEquals(100, first.records.size)
        assertEquals("q0.example.com", first.records.first().domain)
        assertEquals("q99.example.com", first.records.last().domain)
        assertEquals(LogVerdict.BLOCKED, first.records[33].verdict)

        // Nothing new since the cursor.
        val empty = reader.read(since = first.cursor)
        assertTrue(empty.records.isEmpty())
        assertEquals(first.cursor, empty.cursor)

        // Incremental tail.
        for (i in 100 until 130) writer.append(record(i))
        writer.flush()
        val tail = reader.read(since = first.cursor)
        assertEquals(130L, tail.cursor)
        assertEquals(30, tail.records.size)
        assertEquals("q100.example.com", tail.records.first().domain)
        writer.close()
    }

    @Test
    fun wraparoundKeepsNewestCapacityRecords() {
        val file = File(makeTempDirectory(), "log.ring")
        val writer = QueryLogRingWriter(file, capacity = 256, flushThreshold = 1000)

        for (i in 0 until 1000) writer.append(record(i))
        writer.flush()

        val tail = QueryLogRingReader(file).read(since = 0)
        assertEquals(1000L, tail.cursor)
        assertEquals(256, tail.records.size)
        assertEquals("q744.example.com", tail.records.first().domain)
        assertEquals("q999.example.com", tail.records.last().domain)
        writer.close()
    }

    @Test
    fun cursorSurvivesReopen() {
        val file = File(makeTempDirectory(), "log.ring")
        QueryLogRingWriter(file, capacity = 128).use { writer ->
            for (i in 0 until 10) writer.append(record(i))
            writer.flush()
        }
        QueryLogRingWriter(file, capacity = 128).use { writer ->
            writer.append(record(10))
            writer.flush()
        }

        val tail = QueryLogRingReader(file).read(since = 0)
        assertEquals(11L, tail.cursor)
        assertEquals(11, tail.records.size)
        assertEquals("q10.example.com", tail.records.last().domain)
    }

    @Test
    fun tornRecordsAreDiscarded() {
        val file = File(makeTempDirectory(), "log.ring")
        QueryLogRingWriter(file, capacity = 64).use { writer ->
            for (i in 0 until 10) writer.append(record(i))
            writer.flush()
        }

        // Corrupt slot 4 with garbage that cannot validate.
        RandomAccessFile(file, "rw").use { handle ->
            handle.seek(QueryLogRing.HEADER_SIZE + 4L * QueryLogRecord.RECORD_SIZE)
            val garbage = ByteArray(QueryLogRecord.RECORD_SIZE) { 0xFF.toByte() }
            garbage[12] = 200.toByte() // impossible domain length
            handle.write(garbage)
        }

        val tail = QueryLogRingReader(file).read(since = 0)
        assertEquals(10L, tail.cursor)
        assertEquals(9, tail.records.size)
        assertFalse(tail.records.any { it.domain == "q4.example.com" })
    }

    @Test
    fun recordCodecTruncatesLongDomains() {
        val longDomain = "abc.".repeat(30) + "example.com"
        val original = QueryLogRecord(42, 65, LogVerdict.BLOCKED, domain = longDomain)
        val decoded = QueryLogRecord.decode(original.encoded())
        assertNotNull(decoded)
        assertEquals(65, decoded!!.qtype)
        assertEquals(LogVerdict.BLOCKED, decoded.verdict)
        // Keeps the suffix (most-significant part of a hostname).
        assertTrue(decoded.domain.endsWith("example.com"))
        assertEquals(QueryLogRecord.MAX_DOMAIN_BYTES, decoded.domain.toByteArray().size)
    }

    @Test
    fun blankSlotDecodesToNull() {
        assertNull(QueryLogRecord.decode(ByteArray(64)))
    }

    @Test
    fun statsStoreRoundTrip() {
        val file = File(makeTempDirectory(), "stats.json")
        val stats = BlockerStats()
        val day = BlockerStats.dayKey(1_700_000_000_000L)
        for (i in 0 until 10) stats.record(blocked = i % 2 == 0, day = day)
        StatsPersistence.save(stats, file)

        val loaded = StatsPersistence.load(file)
        assertEquals(10L, loaded.totalQueries)
        assertEquals(5L, loaded.totalBlocked)
        assertEquals(DayCounters(total = 10, blocked = 5), loaded.counters(day))
        assertEquals(0L, StatsPersistence.load(File(file.path + ".missing")).totalQueries)
    }

    @Test
    fun statsTrimKeepsNewestDays() {
        val stats = BlockerStats()
        for (day in 1..70) {
            stats.record(blocked = true, day = String.format("2026-01-%02d", day % 31 + 1))
        }
        assertTrue(stats.days.size <= 60)
    }
}
