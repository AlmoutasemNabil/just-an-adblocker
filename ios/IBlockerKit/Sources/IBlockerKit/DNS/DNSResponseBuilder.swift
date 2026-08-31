import Foundation

/// Synthesizes DNS responses locally, without touching the network.
///
/// Responses echo the question section byte-exactly (preserving 0x20 case
/// randomization) and never set TC, so clients never retry over TCP.
/// EDNS0 is deliberately stripped: synthesized responses are tiny.
public enum DNSResponseBuilder {

    /// The response for a blocked name.
    ///
    /// A queries get 0.0.0.0, AAAA queries get ::, and every other qtype
    /// (crucially HTTPS/SVCB type 65/64, which carry address hints that would
    /// otherwise leak past a naive A/AAAA-only blocker) gets NOERROR/NODATA.
    public static func blocked(for query: DNSQuery, ttl: UInt32 = 300) -> Data {
        switch query.qtype {
        case DNSRecordType.a:
            return build(query: query, rcode: 0, answerRData: [0, 0, 0, 0], ttl: ttl)
        case DNSRecordType.aaaa:
            return build(query: query, rcode: 0, answerRData: [UInt8](repeating: 0, count: 16), ttl: ttl)
        default:
            return build(query: query, rcode: 0, answerRData: nil, ttl: ttl)
        }
    }

    public static func nxdomain(for query: DNSQuery) -> Data {
        build(query: query, rcode: 3, answerRData: nil, ttl: 0)
    }

    public static func servfail(for query: DNSQuery) -> Data {
        build(query: query, rcode: 2, answerRData: nil, ttl: 0)
    }

    /// Header-only response with TC set, for the rare upstream answer too
    /// large to fit back through the tunnel MTU unfragmented.
    public static func truncated(for query: DNSQuery) -> Data {
        build(query: query, rcode: 0, answerRData: nil, ttl: 0, extraFlags: 0x0200)
    }

    private static func build(query: DNSQuery, rcode: UInt8, answerRData: [UInt8]?, ttl: UInt32,
                              extraFlags: UInt16 = 0) -> Data {
        var out: [UInt8] = []
        out.reserveCapacity(12 + query.rawQuestion.count + 16 + (answerRData?.count ?? 0))

        // Header: QR=1, opcode=0, AA=0, TC=0, RD echoed, RA=1, Z=0.
        var flags: UInt16 = 0x8000 | 0x0080 | UInt16(rcode & 0x0F) | extraFlags
        flags |= query.flags & 0x0100

        appendU16(&out, query.transactionID)
        appendU16(&out, flags)
        appendU16(&out, 1)                                  // QDCOUNT
        appendU16(&out, answerRData == nil ? 0 : 1)         // ANCOUNT
        appendU16(&out, 0)                                  // NSCOUNT
        appendU16(&out, 0)                                  // ARCOUNT

        out.append(contentsOf: query.rawQuestion)

        if let rdata = answerRData {
            out.append(contentsOf: [0xC0, 0x0C])            // pointer to question name at offset 12
            appendU16(&out, query.qtype)
            appendU16(&out, query.qclass)
            out.append(UInt8((ttl >> 24) & 0xFF))
            out.append(UInt8((ttl >> 16) & 0xFF))
            out.append(UInt8((ttl >> 8) & 0xFF))
            out.append(UInt8(ttl & 0xFF))
            appendU16(&out, UInt16(rdata.count))
            out.append(contentsOf: rdata)
        }

        return Data(out)
    }

    private static func appendU16(_ out: inout [UInt8], _ value: UInt16) {
        out.append(UInt8(value >> 8))
        out.append(UInt8(value & 0xFF))
    }
}
