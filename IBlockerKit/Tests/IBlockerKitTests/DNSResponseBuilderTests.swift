import XCTest
@testable import IBlockerKit

final class DNSResponseBuilderTests: XCTestCase {

    private func parseQuery(_ name: String, qtype: UInt16, id: UInt16 = 0x4242) throws -> DNSQuery {
        try DNSParser.parseQuery(makeDNSQueryData(id: id, name: name, qtype: qtype))
    }

    func testBlockedAReturnsZeroAddress() throws {
        let query = try parseQuery("ads.example.com", qtype: DNSRecordType.a)
        let response = try XCTUnwrap(MiniDNSResponse(DNSResponseBuilder.blocked(for: query)))

        XCTAssertEqual(response.id, 0x4242)
        XCTAssertTrue(response.isResponse)
        XCTAssertTrue(response.recursionAvailable)
        XCTAssertFalse(response.isTruncated)
        XCTAssertEqual(response.rcode, 0)
        XCTAssertEqual(response.qdcount, 1)
        XCTAssertEqual(response.ancount, 1)
        XCTAssertEqual(response.questionName, "ads.example.com")
        XCTAssertEqual(response.answerType, DNSRecordType.a)
        XCTAssertEqual(response.answerRData, [0, 0, 0, 0])
    }

    func testBlockedAAAAReturnsZeroAddress() throws {
        let query = try parseQuery("ads.example.com", qtype: DNSRecordType.aaaa)
        let response = try XCTUnwrap(MiniDNSResponse(DNSResponseBuilder.blocked(for: query)))
        XCTAssertEqual(response.ancount, 1)
        XCTAssertEqual(response.answerType, DNSRecordType.aaaa)
        XCTAssertEqual(response.answerRData, [UInt8](repeating: 0, count: 16))
    }

    func testBlockedHTTPSTypeReturnsNoData() throws {
        // HTTPS (type 65) records carry address hints; answering NODATA
        // instead of ignoring them is what keeps modern Safari from leaking.
        let query = try parseQuery("ads.example.com", qtype: DNSRecordType.https)
        let response = try XCTUnwrap(MiniDNSResponse(DNSResponseBuilder.blocked(for: query)))
        XCTAssertEqual(response.rcode, 0)
        XCTAssertEqual(response.ancount, 0)
    }

    func testNXDomainAndServfailAndTruncated() throws {
        let query = try parseQuery("x.example.com", qtype: DNSRecordType.a)
        XCTAssertEqual(MiniDNSResponse(DNSResponseBuilder.nxdomain(for: query))?.rcode, 3)
        XCTAssertEqual(MiniDNSResponse(DNSResponseBuilder.servfail(for: query))?.rcode, 2)
        let truncated = try XCTUnwrap(MiniDNSResponse(DNSResponseBuilder.truncated(for: query)))
        XCTAssertTrue(truncated.isTruncated)
        XCTAssertEqual(truncated.ancount, 0)
    }

    func testQuestionEchoIsByteExact() throws {
        let data = makeDNSQueryData(id: 1, name: "AdS.ExAmPle.CoM", qtype: DNSRecordType.a)
        let query = try DNSParser.parseQuery(data)
        let response = DNSResponseBuilder.blocked(for: query)
        let echoed = [UInt8](response)[12..<(12 + query.rawQuestion.count)]
        XCTAssertEqual(Array(echoed), query.rawQuestion)
    }

    func testRecursionDesiredIsEchoed() throws {
        let noRD = try DNSParser.parseQuery(makeDNSQueryData(name: "a.example.com", recursionDesired: false))
        let response = try XCTUnwrap(MiniDNSResponse(DNSResponseBuilder.blocked(for: noRD)))
        XCTAssertEqual(response.flags & 0x0100, 0)
    }

    func testResponseParsesWithOwnParserAsResponse() throws {
        // Our own parser must reject what we emit (QR=1) — guards loops.
        let query = try parseQuery("ads.example.com", qtype: DNSRecordType.a)
        XCTAssertThrowsError(try DNSParser.parseQuery(DNSResponseBuilder.blocked(for: query)))
    }
}
