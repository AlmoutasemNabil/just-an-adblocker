import XCTest
@testable import IBlockerKit

final class DNSMessageTests: XCTestCase {

    func testParsesSimpleAQuery() throws {
        let data = makeDNSQueryData(id: 0xBEEF, name: "www.Example.COM", qtype: DNSRecordType.a)
        let query = try DNSParser.parseQuery(data)
        XCTAssertEqual(query.transactionID, 0xBEEF)
        XCTAssertEqual(query.questionName, "www.example.com")
        XCTAssertEqual(query.qtype, DNSRecordType.a)
        XCTAssertEqual(query.qclass, 1)
        XCTAssertTrue(query.recursionDesired)
        XCTAssertFalse(query.hasAdditionalRecords)
        XCTAssertEqual(query.raw, data)
    }

    func testRawQuestionPreservesCase() throws {
        let data = makeDNSQueryData(name: "wWw.ExAmPlE.cOm")
        let query = try DNSParser.parseQuery(data)
        // 0x20 randomization must survive for the response echo.
        let expected = [UInt8](data[12...])
        XCTAssertEqual(query.rawQuestion, expected)
        XCTAssertEqual(query.questionName, "www.example.com")
    }

    func testDetectsEDNS() throws {
        let data = makeDNSQueryData(name: "example.com", edns: true)
        let query = try DNSParser.parseQuery(data)
        XCTAssertTrue(query.hasAdditionalRecords)
    }

    func testRejectsResponses() {
        var bytes = [UInt8](makeDNSQueryData(name: "example.com"))
        bytes[2] |= 0x80
        XCTAssertThrowsError(try DNSParser.parseQuery(Data(bytes))) { error in
            XCTAssertEqual(error as? DNSWireError, .notAQuery)
        }
    }

    func testRejectsMultiQuestion() {
        var bytes = [UInt8](makeDNSQueryData(name: "example.com"))
        bytes[5] = 2
        XCTAssertThrowsError(try DNSParser.parseQuery(Data(bytes))) { error in
            XCTAssertEqual(error as? DNSWireError, .unsupportedQuestionCount)
        }
    }

    func testRejectsTruncatedMessages() {
        let data = makeDNSQueryData(name: "example.com")
        XCTAssertThrowsError(try DNSParser.parseQuery(data.prefix(8)))
        XCTAssertThrowsError(try DNSParser.parseQuery(data.prefix(14)))
    }

    func testNameDecodeFollowsCompressionPointers() throws {
        // Name at offset 12; a pointer to it placed after.
        var bytes = [UInt8](repeating: 0, count: 12)
        bytes.append(contentsOf: [3] + Array("www".utf8))
        bytes.append(contentsOf: [7] + Array("EXAMPLE".utf8))
        bytes.append(contentsOf: [3] + Array("com".utf8))
        bytes.append(0)
        let pointerOffset = bytes.count
        bytes.append(contentsOf: [0xC0, 0x0C])

        let (name, wireLength) = try DNSNameCodec.decode(bytes, at: pointerOffset)
        XCTAssertEqual(name, "www.example.com")
        XCTAssertEqual(wireLength, 2)

        let (direct, directLength) = try DNSNameCodec.decode(bytes, at: 12)
        XCTAssertEqual(direct, "www.example.com")
        XCTAssertEqual(directLength, 17)
    }

    func testNameDecodeRejectsForwardPointerLoops() {
        var bytes = [UInt8](repeating: 0, count: 12)
        let offset = bytes.count
        bytes.append(contentsOf: [0xC0, UInt8(offset)])  // points at itself
        XCTAssertThrowsError(try DNSNameCodec.decode(bytes, at: offset))
    }

    func testNameEncodeRoundTrip() throws {
        let encoded = try XCTUnwrap(DNSNameCodec.encode("ads.tracker.example.org"))
        let (decoded, length) = try DNSNameCodec.decode(encoded, at: 0)
        XCTAssertEqual(decoded, "ads.tracker.example.org")
        XCTAssertEqual(length, encoded.count)
        XCTAssertNil(DNSNameCodec.encode("bad..label"))
    }
}
