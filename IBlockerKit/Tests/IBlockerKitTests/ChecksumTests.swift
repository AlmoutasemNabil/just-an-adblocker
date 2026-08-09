import XCTest
@testable import IBlockerKit

final class ChecksumTests: XCTestCase {

    /// The classic worked example (appears in countless texts): IPv4 header
    /// whose correct checksum is 0xB1E6.
    func testIPv4HeaderKnownVector() {
        let header: [UInt8] = [
            0x45, 0x00, 0x00, 0x3C, 0x1C, 0x46, 0x40, 0x00,
            0x40, 0x06, 0x00, 0x00, 0xAC, 0x10, 0x0A, 0x63,
            0xAC, 0x10, 0x0A, 0x0C,
        ]
        XCTAssertEqual(InternetChecksum.ipv4Header(header), 0xB1E6)

        var complete = header
        complete[10] = 0xB1
        complete[11] = 0xE6
        XCTAssertTrue(ipv4HeaderChecksumIsValid(complete))
    }

    func testOddLengthPadding() {
        // Odd byte is padded on the right: [0x01] sums as 0x0100.
        XCTAssertEqual(InternetChecksum.sum16([0x01]), 0x0100)
        XCTAssertEqual(InternetChecksum.sum16([0x00, 0x01, 0x02]), 0x0001 + 0x0200)
    }

    func testCarryFolding() {
        // 0xFFFF + 0xFFFF requires folding twice.
        XCTAssertEqual(InternetChecksum.finalize(0x1FFFE), ~UInt16(0xFFFF))
        XCTAssertEqual(InternetChecksum.finalize(0), 0xFFFF)
    }

    func testUDPv4ChecksumValidatesRoundTrip() {
        let src: [UInt8] = [192, 168, 1, 10]
        let dst: [UInt8] = [198, 18, 0, 2]
        var segment: [UInt8] = [0xD9, 0x03, 0x00, 0x35, 0x00, 0x0D, 0x00, 0x00]
        segment.append(contentsOf: Array("hello".utf8))

        let checksum = InternetChecksum.udp(ipVersion: 4, source: src, destination: dst, segment: segment)
        XCTAssertNotEqual(checksum, 0)
        segment[6] = UInt8(checksum >> 8)
        segment[7] = UInt8(checksum & 0xFF)
        XCTAssertTrue(udpChecksumIsValid(ipVersion: 4, source: src, destination: dst, segment: segment))
    }

    func testUDPv6ChecksumValidatesRoundTrip() {
        let src = [UInt8](repeating: 0xFD, count: 16)
        var dst = [UInt8](repeating: 0, count: 16); dst[15] = 2
        var segment: [UInt8] = [0xD9, 0x04, 0x00, 0x35, 0x00, 0x0C, 0x00, 0x00]
        segment.append(contentsOf: Array("ipv6".utf8))

        let checksum = InternetChecksum.udp(ipVersion: 6, source: src, destination: dst, segment: segment)
        segment[6] = UInt8(checksum >> 8)
        segment[7] = UInt8(checksum & 0xFF)
        XCTAssertTrue(udpChecksumIsValid(ipVersion: 6, source: src, destination: dst, segment: segment))
    }

    func testZeroChecksumTransmitsAsFFFF() {
        // Craft a segment whose ones-complement sum is 0xFFFF so the computed
        // checksum would be 0x0000: RFC 768 requires transmitting 0xFFFF.
        // sum(pseudo v4) = src+dst+proto+len; make payload cancel it out.
        let src: [UInt8] = [0, 0, 0, 0]
        let dst: [UInt8] = [0, 0, 0, 0]
        // segment: ports 0, length 10, checksum 0, payload = two bytes chosen below
        var segment: [UInt8] = [0, 0, 0, 0, 0, 10, 0, 0, 0, 0]
        // Current sum: len(pseudo)=10 + proto 17 + segment len field 10 = ...
        var sum: UInt32 = 17 + 10
        sum = InternetChecksum.sum16(segment, initial: sum)
        // Add payload word that brings the folded sum to 0xFFFF.
        var folded = sum
        while folded > 0xFFFF { folded = (folded & 0xFFFF) + (folded >> 16) }
        let needed = UInt16(0xFFFF - folded)
        segment[8] = UInt8(needed >> 8)
        segment[9] = UInt8(needed & 0xFF)

        let checksum = InternetChecksum.udp(ipVersion: 4, source: src, destination: dst, segment: segment)
        XCTAssertEqual(checksum, 0xFFFF)
    }
}
