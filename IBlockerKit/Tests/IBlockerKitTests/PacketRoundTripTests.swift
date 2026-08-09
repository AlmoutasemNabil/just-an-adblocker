import XCTest
@testable import IBlockerKit

final class PacketRoundTripTests: XCTestCase {

    func testParseIPv4UDP() throws {
        let payload = [UInt8](makeDNSQueryData(name: "example.com"))
        let packet = makeUDPPacketV4(sourcePort: 50000, payload: payload)

        let parsed = try XCTUnwrap(PacketParser.parseUDP(packet))
        XCTAssertEqual(parsed.ipVersion, 4)
        XCTAssertEqual(parsed.sourceAddress, [10, 0, 0, 5])
        XCTAssertEqual(parsed.destinationAddress, [198, 18, 0, 2])
        XCTAssertEqual(parsed.sourcePort, 50000)
        XCTAssertEqual(parsed.destinationPort, 53)
        XCTAssertEqual(parsed.payload, payload)
    }

    func testParseIPv6UDP() throws {
        let payload = [UInt8](makeDNSQueryData(name: "example.com", qtype: DNSRecordType.aaaa))
        let packet = makeUDPPacketV6(payload: payload)

        let parsed = try XCTUnwrap(PacketParser.parseUDP(packet))
        XCTAssertEqual(parsed.ipVersion, 6)
        XCTAssertEqual(parsed.destinationPort, 53)
        XCTAssertEqual(parsed.payload, payload)
        XCTAssertEqual(parsed.destinationAddress.count, 16)
    }

    func testRejectsFragmentsTCPAndGarbage() {
        let payload = [UInt8](makeDNSQueryData(name: "example.com"))
        var fragmented = [UInt8](makeUDPPacketV4(payload: payload))
        fragmented[6] = 0x20  // MF flag
        XCTAssertNil(PacketParser.parseUDP(Data(fragmented)))

        var offsetFragment = [UInt8](makeUDPPacketV4(payload: payload))
        offsetFragment[6] = 0x00
        offsetFragment[7] = 0x08  // fragment offset 8
        XCTAssertNil(PacketParser.parseUDP(Data(offsetFragment)))

        var tcp = [UInt8](makeUDPPacketV4(payload: payload))
        tcp[9] = 6
        XCTAssertNil(PacketParser.parseUDP(Data(tcp)))

        XCTAssertNil(PacketParser.parseUDP(Data([0x45, 0x00])))
        XCTAssertNil(PacketParser.parseUDP(Data()))
        XCTAssertNil(PacketParser.parseUDP(Data([0x10, 0x00, 0x00])))
    }

    func testIPv4ReplySwapsTupleAndChecksums() throws {
        let payload = [UInt8](makeDNSQueryData(name: "example.com"))
        let request = try XCTUnwrap(PacketParser.parseUDP(makeUDPPacketV4(sourcePort: 43210, payload: payload)))

        let responsePayload = [UInt8]("response".utf8)
        let reply = try XCTUnwrap(UDPReplyBuilder.reply(to: request, payload: responsePayload))
        let bytes = [UInt8](reply)

        // The reply must itself parse, with the tuple reversed.
        let parsedReply = try XCTUnwrap(PacketParser.parseUDP(reply))
        XCTAssertEqual(parsedReply.sourceAddress, [198, 18, 0, 2])
        XCTAssertEqual(parsedReply.destinationAddress, [10, 0, 0, 5])
        XCTAssertEqual(parsedReply.sourcePort, 53)
        XCTAssertEqual(parsedReply.destinationPort, 43210)
        XCTAssertEqual(parsedReply.payload, responsePayload)

        // Checksums must be valid.
        XCTAssertTrue(ipv4HeaderChecksumIsValid(Array(bytes[0..<20])))
        XCTAssertTrue(udpChecksumIsValid(
            ipVersion: 4,
            source: parsedReply.sourceAddress,
            destination: parsedReply.destinationAddress,
            segment: Array(bytes[20...])
        ))
    }

    func testIPv6ReplySwapsTupleAndChecksums() throws {
        let payload = [UInt8](makeDNSQueryData(name: "example.com", qtype: DNSRecordType.aaaa))
        let request = try XCTUnwrap(PacketParser.parseUDP(makeUDPPacketV6(sourcePort: 44444, payload: payload)))

        let responsePayload = [UInt8]("v6-response".utf8)
        let reply = try XCTUnwrap(UDPReplyBuilder.reply(to: request, payload: responsePayload))
        let bytes = [UInt8](reply)

        let parsedReply = try XCTUnwrap(PacketParser.parseUDP(reply))
        XCTAssertEqual(parsedReply.ipVersion, 6)
        XCTAssertEqual(parsedReply.sourcePort, 53)
        XCTAssertEqual(parsedReply.destinationPort, 44444)
        XCTAssertEqual(parsedReply.payload, responsePayload)
        XCTAssertEqual(parsedReply.sourceAddress, request.destinationAddress)
        XCTAssertEqual(parsedReply.destinationAddress, request.sourceAddress)

        XCTAssertTrue(udpChecksumIsValid(
            ipVersion: 6,
            source: parsedReply.sourceAddress,
            destination: parsedReply.destinationAddress,
            segment: Array(bytes[40...])
        ))
    }
}
