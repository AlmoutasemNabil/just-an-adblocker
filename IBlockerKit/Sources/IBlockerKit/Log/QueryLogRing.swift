import Foundation

/// Fixed-size ring file for query-log records, written by the tunnel and
/// tailed by the app. Layout:
///
///     0     "IBLG" magic
///     4     version u32 LE (=1)
///     8     recordSize u32 LE (=64)
///     12    capacity u32 LE
///     16    writeCursor u64 LE  (monotonic record index, never wraps)
///     4096  capacity × 64-byte records, slot = cursor % capacity
///
/// Bounded disk by construction, O(1) append, and torn writes damage at most
/// one 64-byte slot, which the reader's validation discards. No locking:
/// the cursor is published after the records land; readers double-check
/// record contents.
public enum QueryLogRing {
    public static let magic: [UInt8] = Array("IBLG".utf8)
    public static let version: UInt32 = 1
    public static let headerSize: UInt64 = 4096
    public static let defaultCapacity: UInt32 = 65536
}

public final class QueryLogRingWriter {
    private let handle: FileHandle
    private let capacity: UInt32
    private var cursor: UInt64
    private var pending: [QueryLogRecord] = []
    private let flushThreshold: Int

    public init(url: URL, capacity: UInt32 = QueryLogRing.defaultCapacity, flushThreshold: Int = 128) throws {
        self.capacity = capacity
        self.flushThreshold = flushThreshold

        let fm = FileManager.default
        var needsInit = !fm.fileExists(atPath: url.path)
        if !needsInit {
            // Re-validate an existing file; recreate on mismatch.
            if let existing = FileHandle(forReadingAtPath: url.path) {
                let header = (try? existing.read(upToCount: 24)) ?? Data()
                try? existing.close()
                if header.count < 24
                    || Array(header.prefix(4)) != QueryLogRing.magic
                    || Self.readU32(header, at: 12) != capacity {
                    needsInit = true
                }
            } else {
                needsInit = true
            }
        }

        if needsInit {
            var header = Data()
            header.append(contentsOf: QueryLogRing.magic)
            Self.appendU32(&header, QueryLogRing.version)
            Self.appendU32(&header, UInt32(QueryLogRecord.recordSize))
            Self.appendU32(&header, capacity)
            var zero = UInt64(0).littleEndian
            withUnsafeBytes(of: &zero) { header.append(contentsOf: $0) }
            header.append(Data(count: Int(QueryLogRing.headerSize) - header.count))
            try header.write(to: url, options: .atomic)
        }

        self.handle = try FileHandle(forUpdating: url)

        try handle.seek(toOffset: 16)
        let cursorData = (try? handle.read(upToCount: 8)) ?? Data()
        self.cursor = cursorData.count == 8 ? Self.readU64(cursorData, at: 0) : 0

        if needsInit {
            try handle.truncate(atOffset: QueryLogRing.headerSize + UInt64(capacity) * UInt64(QueryLogRecord.recordSize))
        }
    }

    deinit {
        try? flush()
        try? handle.close()
    }

    public func append(_ record: QueryLogRecord) {
        pending.append(record)
        if pending.count >= flushThreshold {
            try? flush()
        }
    }

    public var pendingCount: Int { pending.count }

    public func flush() throws {
        guard !pending.isEmpty else { return }
        let records = pending
        pending.removeAll(keepingCapacity: true)

        // Records may wrap the ring once; emit one or two contiguous writes.
        var index = 0
        while index < records.count {
            let slot = (cursor + UInt64(index)) % UInt64(capacity)
            let slotsUntilWrap = Int(UInt64(capacity) - slot)
            let batch = min(slotsUntilWrap, records.count - index)

            var buffer = [UInt8]()
            buffer.reserveCapacity(batch * QueryLogRecord.recordSize)
            for record in records[index..<(index + batch)] {
                buffer.append(contentsOf: record.encoded())
            }

            let offset = QueryLogRing.headerSize + slot * UInt64(QueryLogRecord.recordSize)
            try handle.seek(toOffset: offset)
            try handle.write(contentsOf: Data(buffer))
            index += batch
        }

        cursor += UInt64(records.count)
        var cursorLE = cursor.littleEndian
        var cursorData = Data()
        withUnsafeBytes(of: &cursorLE) { cursorData.append(contentsOf: $0) }
        try handle.seek(toOffset: 16)
        try handle.write(contentsOf: cursorData)
    }

    static func readU32(_ data: Data, at offset: Int) -> UInt32 {
        data.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)) }
    }

    static func readU64(_ data: Data, at offset: Int) -> UInt64 {
        data.withUnsafeBytes { UInt64(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self)) }
    }

    static func appendU32(_ data: inout Data, _ value: UInt32) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }
}

public final class QueryLogRingReader {
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    /// Tail of the ring: all records with index in [max(since, cursor-capacity), cursor),
    /// oldest first, capped at `limit` newest. Returns the new cursor to pass
    /// back on the next poll.
    public func read(since: UInt64 = 0, limit: Int = 4000) -> (records: [QueryLogRecord], cursor: UInt64) {
        guard let handle = FileHandle(forReadingAtPath: url.path) else { return ([], since) }
        defer { try? handle.close() }

        guard let header = try? handle.read(upToCount: 24), header.count == 24,
              Array(header.prefix(4)) == QueryLogRing.magic else { return ([], since) }
        let capacity = UInt64(QueryLogRingWriter.readU32(header, at: 12))
        guard capacity > 0 else { return ([], since) }
        let cursor = QueryLogRingWriter.readU64(header, at: 16)
        guard cursor > since else { return ([], cursor) }

        var start = max(since, cursor > capacity ? cursor - capacity : 0)
        if cursor - start > UInt64(limit) {
            start = cursor - UInt64(limit)
        }

        var records: [QueryLogRecord] = []
        records.reserveCapacity(Int(cursor - start))
        var index = start
        while index < cursor {
            let slot = index % capacity
            let slotsUntilWrap = capacity - slot
            let batch = min(slotsUntilWrap, cursor - index)
            let offset = QueryLogRing.headerSize + slot * UInt64(QueryLogRecord.recordSize)

            guard (try? handle.seek(toOffset: offset)) != nil,
                  let data = try? handle.read(upToCount: Int(batch) * QueryLogRecord.recordSize),
                  !data.isEmpty else { break }

            let bytes = [UInt8](data)
            var recordStart = 0
            while recordStart + QueryLogRecord.recordSize <= bytes.count {
                if let record = QueryLogRecord.decode(bytes[recordStart..<(recordStart + QueryLogRecord.recordSize)]) {
                    records.append(record)
                }
                recordStart += QueryLogRecord.recordSize
            }
            index += batch
        }

        return (records, cursor)
    }
}
