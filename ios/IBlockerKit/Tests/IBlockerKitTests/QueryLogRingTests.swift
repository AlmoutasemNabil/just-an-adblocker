import XCTest
@testable import IBlockerKit

final class QueryLogRingTests: XCTestCase {

    private func record(_ i: Int, domain: String = "example.com") -> QueryLogRecord {
        QueryLogRecord(
            timestampMillis: UInt64(1_700_000_000_000 + i),
            qtype: DNSRecordType.a,
            verdict: i % 3 == 0 ? .blocked : .allowed,
            domain: "q\(i).\(domain)"
        )
    }

    func testAppendFlushRead() throws {
        let url = try makeTempDirectory().appendingPathComponent("log.ring")
        let writer = try QueryLogRingWriter(url: url, capacity: 1024, flushThreshold: 64)

        for i in 0..<100 { writer.append(record(i)) }
        try writer.flush()

        let reader = QueryLogRingReader(url: url)
        let (records, cursor) = reader.read(since: 0)
        XCTAssertEqual(cursor, 100)
        XCTAssertEqual(records.count, 100)
        XCTAssertEqual(records.first?.domain, "q0.example.com")
        XCTAssertEqual(records.last?.domain, "q99.example.com")
        XCTAssertEqual(records[33].verdict, .blocked)

        // Nothing new since the cursor.
        let (empty, sameCursor) = reader.read(since: cursor)
        XCTAssertTrue(empty.isEmpty)
        XCTAssertEqual(sameCursor, cursor)

        // Incremental tail.
        for i in 100..<130 { writer.append(record(i)) }
        try writer.flush()
        let (tail, newCursor) = reader.read(since: cursor)
        XCTAssertEqual(newCursor, 130)
        XCTAssertEqual(tail.count, 30)
        XCTAssertEqual(tail.first?.domain, "q100.example.com")
    }

    func testWraparoundKeepsNewestCapacityRecords() throws {
        let url = try makeTempDirectory().appendingPathComponent("log.ring")
        let writer = try QueryLogRingWriter(url: url, capacity: 256, flushThreshold: 1000)

        for i in 0..<1000 { writer.append(record(i)) }
        try writer.flush()

        let (records, cursor) = QueryLogRingReader(url: url).read(since: 0)
        XCTAssertEqual(cursor, 1000)
        XCTAssertEqual(records.count, 256)
        XCTAssertEqual(records.first?.domain, "q744.example.com")
        XCTAssertEqual(records.last?.domain, "q999.example.com")
    }

    func testCursorSurvivesReopen() throws {
        let url = try makeTempDirectory().appendingPathComponent("log.ring")
        do {
            let writer = try QueryLogRingWriter(url: url, capacity: 128)
            for i in 0..<10 { writer.append(record(i)) }
            try writer.flush()
        }
        let writer2 = try QueryLogRingWriter(url: url, capacity: 128)
        writer2.append(record(10))
        try writer2.flush()

        let (records, cursor) = QueryLogRingReader(url: url).read(since: 0)
        XCTAssertEqual(cursor, 11)
        XCTAssertEqual(records.count, 11)
        XCTAssertEqual(records.last?.domain, "q10.example.com")
    }

    func testTornRecordsAreDiscarded() throws {
        let url = try makeTempDirectory().appendingPathComponent("log.ring")
        let writer = try QueryLogRingWriter(url: url, capacity: 64)
        for i in 0..<10 { writer.append(record(i)) }
        try writer.flush()

        // Corrupt slot 4 with garbage that cannot validate.
        let handle = try FileHandle(forUpdating: url)
        try handle.seek(toOffset: QueryLogRing.headerSize + 4 * UInt64(QueryLogRecord.recordSize))
        var garbage = [UInt8](repeating: 0xFF, count: QueryLogRecord.recordSize)
        garbage[12] = 200  // impossible domain length
        try handle.write(contentsOf: Data(garbage))
        try handle.close()

        let (records, cursor) = QueryLogRingReader(url: url).read(since: 0)
        XCTAssertEqual(cursor, 10)
        XCTAssertEqual(records.count, 9)
        XCTAssertFalse(records.contains { $0.domain == "q4.example.com" })
    }

    func testRecordCodecTruncatesLongDomains() {
        let longDomain = String(repeating: "abc.", count: 30) + "example.com"
        let original = QueryLogRecord(timestampMillis: 42, qtype: 65, verdict: .blocked, domain: longDomain)
        let decoded = QueryLogRecord.decode(original.encoded()[...])
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.qtype, 65)
        XCTAssertEqual(decoded?.verdict, .blocked)
        // Keeps the suffix (most-significant part of a hostname).
        XCTAssertTrue(decoded!.domain.hasSuffix("example.com"))
        XCTAssertEqual(decoded!.domain.utf8.count, QueryLogRecord.maxDomainBytes)
    }

    func testBlankSlotDecodesToNil() {
        XCTAssertNil(QueryLogRecord.decode([UInt8](repeating: 0, count: 64)[...]))
    }

    func testStatsStoreRoundTrip() throws {
        let url = try makeTempDirectory().appendingPathComponent("stats.json")
        var stats = BlockerStats()
        let day = BlockerStats.dayKey(for: Date(timeIntervalSince1970: 1_700_000_000))
        for i in 0..<10 { stats.record(blocked: i % 2 == 0, on: day) }
        StatsPersistence.save(stats, to: url)

        let loaded = StatsPersistence.load(from: url)
        XCTAssertEqual(loaded.totalQueries, 10)
        XCTAssertEqual(loaded.totalBlocked, 5)
        XCTAssertEqual(loaded.counters(day: day), DayCounters(total: 10, blocked: 5))
        XCTAssertEqual(StatsPersistence.load(from: url.appendingPathExtension("missing")).totalQueries, 0)
    }
}
