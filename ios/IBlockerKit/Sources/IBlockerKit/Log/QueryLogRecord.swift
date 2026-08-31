import Foundation

public enum LogVerdict: UInt8, Sendable, Codable {
    case allowed = 0
    case blocked = 1
    case failed = 2
}

/// One DNS decision, encoded as a fixed 64-byte record:
///
///     0   timestampMillis  u64 LE
///     8   qtype            u16 LE
///     10  verdict          u8
///     11  listId           u8   (reserved, 0 in v1)
///     12  domainLen        u8
///     13  flags            u8   (reserved)
///     14  reserved         u16
///     16  domain UTF-8, up to 48 bytes, zero padded
public struct QueryLogRecord: Sendable, Equatable {
    public static let recordSize = 64
    public static let maxDomainBytes = 48

    public var timestampMillis: UInt64
    public var qtype: UInt16
    public var verdict: LogVerdict
    public var listId: UInt8
    public var domain: String

    public init(timestampMillis: UInt64, qtype: UInt16, verdict: LogVerdict,
                listId: UInt8 = 0, domain: String) {
        self.timestampMillis = timestampMillis
        self.qtype = qtype
        self.verdict = verdict
        self.listId = listId
        self.domain = domain
    }

    public var date: Date {
        Date(timeIntervalSince1970: TimeInterval(timestampMillis) / 1000)
    }

    public func encoded() -> [UInt8] {
        var out = [UInt8](repeating: 0, count: Self.recordSize)
        var ts = timestampMillis.littleEndian
        withUnsafeBytes(of: &ts) { for (i, b) in $0.enumerated() { out[i] = b } }
        out[8] = UInt8(qtype & 0xFF)
        out[9] = UInt8(qtype >> 8)
        out[10] = verdict.rawValue
        out[11] = listId

        var domainBytes = Array(domain.utf8)
        if domainBytes.count > Self.maxDomainBytes {
            domainBytes = Array(domainBytes.suffix(Self.maxDomainBytes))
        }
        out[12] = UInt8(domainBytes.count)
        for (i, b) in domainBytes.enumerated() { out[16 + i] = b }
        return out
    }

    /// Decodes one 64-byte slot; returns nil for torn/blank slots.
    public static func decode(_ bytes: ArraySlice<UInt8>) -> QueryLogRecord? {
        guard bytes.count == recordSize else { return nil }
        let b = Array(bytes)

        var ts: UInt64 = 0
        for i in (0..<8).reversed() { ts = ts << 8 | UInt64(b[i]) }
        guard ts != 0 else { return nil }

        let qtype = UInt16(b[8]) | UInt16(b[9]) << 8
        guard let verdict = LogVerdict(rawValue: b[10]) else { return nil }

        let domainLength = Int(b[12])
        guard domainLength > 0, domainLength <= maxDomainBytes else { return nil }
        let domainBytes = Array(b[16..<(16 + domainLength)])
        guard domainBytes.allSatisfy({ $0 >= 0x21 && $0 <= 0x7E }) else { return nil }

        return QueryLogRecord(
            timestampMillis: ts,
            qtype: qtype,
            verdict: verdict,
            listId: b[11],
            domain: String(decoding: domainBytes, as: UTF8.self)
        )
    }
}
