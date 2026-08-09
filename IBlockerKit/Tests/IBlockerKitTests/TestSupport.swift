import Foundation
import XCTest
@testable import IBlockerKit

// MARK: - DNS message construction

func makeDNSQueryData(id: UInt16 = 0x1234, name: String, qtype: UInt16 = DNSRecordType.a,
                      recursionDesired: Bool = true, edns: Bool = false) -> Data {
    var out: [UInt8] = []
    out.append(UInt8(id >> 8)); out.append(UInt8(id & 0xFF))
    let flags: UInt16 = recursionDesired ? 0x0100 : 0
    out.append(UInt8(flags >> 8)); out.append(UInt8(flags & 0xFF))
    out.append(contentsOf: [0, 1])                    // QDCOUNT
    out.append(contentsOf: [0, 0, 0, 0])              // ANCOUNT, NSCOUNT
    out.append(contentsOf: edns ? [0, 1] : [0, 0])    // ARCOUNT

    for label in name.split(separator: ".") {
        let bytes = Array(label.utf8)
        out.append(UInt8(bytes.count))
        out.append(contentsOf: bytes)
    }
    out.append(0)
    out.append(UInt8(qtype >> 8)); out.append(UInt8(qtype & 0xFF))
    out.append(contentsOf: [0, 1])                    // IN

    if edns {
        out.append(0)                                  // root name
        out.append(contentsOf: [0, 41])                // OPT
        out.append(contentsOf: [0x10, 0x00])           // 4096 payload size
        out.append(contentsOf: [0, 0, 0, 0])           // extended flags
        out.append(contentsOf: [0, 0])                 // RDLEN
    }
    return Data(out)
}

// MARK: - Minimal response reader (independent of production code paths)

struct MiniDNSResponse {
    var id: UInt16
    var flags: UInt16
    var qdcount: Int
    var ancount: Int
    var questionName: String
    var answerType: UInt16?
    var answerRData: [UInt8]?

    var rcode: UInt8 { UInt8(flags & 0x0F) }
    var isResponse: Bool { flags & 0x8000 != 0 }
    var isTruncated: Bool { flags & 0x0200 != 0 }
    var recursionAvailable: Bool { flags & 0x0080 != 0 }

    init?(_ data: Data) {
        let b = [UInt8](data)
        guard b.count >= 12 else { return nil }
        id = UInt16(b[0]) << 8 | UInt16(b[1])
        flags = UInt16(b[2]) << 8 | UInt16(b[3])
        qdcount = Int(b[4]) << 8 | Int(b[5])
        ancount = Int(b[6]) << 8 | Int(b[7])

        var i = 12
        var labels: [String] = []
        while i < b.count, b[i] != 0 {
            let len = Int(b[i])
            guard len & 0xC0 == 0, i + 1 + len <= b.count else { return nil }
            labels.append(String(decoding: b[(i + 1)...(i + len)], as: UTF8.self).lowercased())
            i += len + 1
        }
        guard i + 5 <= b.count else { return nil }
        questionName = labels.joined(separator: ".")
        i += 5  // zero label + qtype + qclass... (qtype starts at i+1)
        i -= 4
        i += 4  // now positioned after question

        answerType = nil
        answerRData = nil
        if ancount >= 1 {
            guard i + 12 <= b.count, b[i] == 0xC0, b[i + 1] == 0x0C else { return nil }
            let type = UInt16(b[i + 2]) << 8 | UInt16(b[i + 3])
            let rdlen = Int(b[i + 10]) << 8 | Int(b[i + 11])
            guard i + 12 + rdlen <= b.count else { return nil }
            answerType = type
            answerRData = Array(b[(i + 12)..<(i + 12 + rdlen)])
        }
    }
}

// MARK: - Raw packet construction

func makeUDPPacketV4(source: [UInt8] = [10, 0, 0, 5], destination: [UInt8] = [198, 18, 0, 2],
                     sourcePort: UInt16 = 55555, destinationPort: UInt16 = 53,
                     payload: [UInt8]) -> Data {
    let udpLength = 8 + payload.count
    let totalLength = 20 + udpLength

    var header: [UInt8] = [
        0x45, 0x00,
        UInt8(totalLength >> 8), UInt8(totalLength & 0xFF),
        0xAB, 0xCD,
        0x40, 0x00,
        64, 17,
        0x00, 0x00,
    ]
    header.append(contentsOf: source)
    header.append(contentsOf: destination)
    let hc = InternetChecksum.ipv4Header(header)
    header[10] = UInt8(hc >> 8); header[11] = UInt8(hc & 0xFF)

    var segment: [UInt8] = [
        UInt8(sourcePort >> 8), UInt8(sourcePort & 0xFF),
        UInt8(destinationPort >> 8), UInt8(destinationPort & 0xFF),
        UInt8(udpLength >> 8), UInt8(udpLength & 0xFF),
        0x00, 0x00,
    ]
    segment.append(contentsOf: payload)
    let uc = InternetChecksum.udp(ipVersion: 4, source: source, destination: destination, segment: segment)
    segment[6] = UInt8(uc >> 8); segment[7] = UInt8(uc & 0xFF)

    return Data(header + segment)
}

func makeUDPPacketV6(source: [UInt8]? = nil, destination: [UInt8]? = nil,
                     sourcePort: UInt16 = 55556, destinationPort: UInt16 = 53,
                     payload: [UInt8]) -> Data {
    let src = source ?? [0xfd, 0x00] + [UInt8](repeating: 0, count: 13) + [0x10]
    let dst = destination ?? [0xfd, 0x00] + [UInt8](repeating: 0, count: 13) + [0x02]
    let udpLength = 8 + payload.count

    var header: [UInt8] = [
        0x60, 0x00, 0x00, 0x00,
        UInt8(udpLength >> 8), UInt8(udpLength & 0xFF),
        17, 64,
    ]
    header.append(contentsOf: src)
    header.append(contentsOf: dst)

    var segment: [UInt8] = [
        UInt8(sourcePort >> 8), UInt8(sourcePort & 0xFF),
        UInt8(destinationPort >> 8), UInt8(destinationPort & 0xFF),
        UInt8(udpLength >> 8), UInt8(udpLength & 0xFF),
        0x00, 0x00,
    ]
    segment.append(contentsOf: payload)
    let uc = InternetChecksum.udp(ipVersion: 6, source: src, destination: dst, segment: segment)
    segment[6] = UInt8(uc >> 8); segment[7] = UInt8(uc & 0xFF)

    return Data(header + segment)
}

// MARK: - Checksum verification (a checksummed span folds to 0)

func ipv4HeaderChecksumIsValid(_ header: [UInt8]) -> Bool {
    InternetChecksum.finalize(InternetChecksum.sum16(header)) == 0
}

func udpChecksumIsValid(ipVersion: Int, source: [UInt8], destination: [UInt8], segment: [UInt8]) -> Bool {
    var sum: UInt32 = 0
    sum = InternetChecksum.sum16(source, initial: sum)
    sum = InternetChecksum.sum16(destination, initial: sum)
    let length = UInt32(segment.count)
    if ipVersion == 4 {
        sum &+= 17
        sum &+= length & 0xFFFF
    } else {
        sum &+= (length >> 16) & 0xFFFF
        sum &+= length & 0xFFFF
        sum &+= 17
    }
    sum = InternetChecksum.sum16(segment, initial: sum)
    return InternetChecksum.finalize(sum) == 0
}

// MARK: - Misc

func makeTempDirectory(_ function: StaticString = #function) throws -> URL {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("iblocker-tests-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

/// Deterministic pseudo-random generator for property tests.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64
    init(seed: UInt64) { state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
    mutating func next() -> UInt64 {
        state ^= state << 13
        state ^= state >> 7
        state ^= state << 17
        return state
    }
}
