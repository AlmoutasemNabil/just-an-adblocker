import Foundation

/// Well-known DNS record types the app cares about (display + blocking policy).
public enum DNSRecordType {
    public static let a: UInt16 = 1
    public static let ns: UInt16 = 2
    public static let cname: UInt16 = 5
    public static let soa: UInt16 = 6
    public static let ptr: UInt16 = 12
    public static let mx: UInt16 = 15
    public static let txt: UInt16 = 16
    public static let aaaa: UInt16 = 28
    public static let srv: UInt16 = 33
    public static let opt: UInt16 = 41
    public static let svcb: UInt16 = 64
    public static let https: UInt16 = 65

    public static func name(_ qtype: UInt16) -> String {
        switch qtype {
        case a: return "A"
        case ns: return "NS"
        case cname: return "CNAME"
        case soa: return "SOA"
        case ptr: return "PTR"
        case mx: return "MX"
        case txt: return "TXT"
        case aaaa: return "AAAA"
        case srv: return "SRV"
        case opt: return "OPT"
        case svcb: return "SVCB"
        case https: return "HTTPS"
        default: return "TYPE\(qtype)"
        }
    }
}

/// A parsed DNS query (QR=0, one question).
public struct DNSQuery: Sendable {
    public let transactionID: UInt16
    public let flags: UInt16
    /// Lowercased dotted question name, no trailing dot.
    public let questionName: String
    /// Byte-exact question section (name + qtype + qclass) as it appeared on
    /// the wire, preserving the original case (DNS 0x20 randomization).
    public let rawQuestion: [UInt8]
    public let qtype: UInt16
    public let qclass: UInt16
    /// True when the query carried anything in the additional section
    /// (in practice: an EDNS0 OPT record).
    public let hasAdditionalRecords: Bool
    /// The complete original message, for untouched upstream forwarding.
    public let raw: Data

    public var recursionDesired: Bool { flags & 0x0100 != 0 }
}

public enum DNSParser {

    /// Parses a standard query with exactly one question.
    /// Throws for responses and multi-question messages; callers should
    /// forward those upstream untouched rather than fail the lookup.
    public static func parseQuery(_ data: Data) throws -> DNSQuery {
        let bytes = [UInt8](data)
        guard bytes.count >= 12 else { throw DNSWireError.truncated }

        let id = UInt16(bytes[0]) << 8 | UInt16(bytes[1])
        let flags = UInt16(bytes[2]) << 8 | UInt16(bytes[3])
        guard flags & 0x8000 == 0 else { throw DNSWireError.notAQuery }

        let qdcount = Int(bytes[4]) << 8 | Int(bytes[5])
        let arcount = Int(bytes[10]) << 8 | Int(bytes[11])
        guard qdcount == 1 else { throw DNSWireError.unsupportedQuestionCount }

        let (name, nameLength) = try DNSNameCodec.decode(bytes, at: 12)
        let qtypeOffset = 12 + nameLength
        guard qtypeOffset + 4 <= bytes.count else { throw DNSWireError.truncated }

        let qtype = UInt16(bytes[qtypeOffset]) << 8 | UInt16(bytes[qtypeOffset + 1])
        let qclass = UInt16(bytes[qtypeOffset + 2]) << 8 | UInt16(bytes[qtypeOffset + 3])

        return DNSQuery(
            transactionID: id,
            flags: flags,
            questionName: name,
            rawQuestion: Array(bytes[12..<(qtypeOffset + 4)]),
            qtype: qtype,
            qclass: qclass,
            hasAdditionalRecords: arcount > 0,
            raw: data
        )
    }
}
