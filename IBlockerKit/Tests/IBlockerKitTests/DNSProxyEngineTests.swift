import XCTest
@testable import IBlockerKit

private final class MockUpstream: DNSUpstream, @unchecked Sendable {
    let handler: @Sendable (Data) async throws -> Data
    init(_ handler: @escaping @Sendable (Data) async throws -> Data) {
        self.handler = handler
    }
    func resolve(_ query: Data) async throws -> Data {
        try await handler(query)
    }
}

/// Echoes the query back with QR set — a structurally valid "answer".
private func fakeAnswer(for query: Data) -> Data {
    var bytes = [UInt8](query)
    bytes[2] |= 0x80
    return Data(bytes)
}

final class DNSProxyEngineTests: XCTestCase {

    private var paths: AppGroupPaths!

    override func setUpWithError() throws {
        paths = AppGroupPaths(containerURL: try makeTempDirectory())
        try paths.ensureDirectories()

        let blocked = ["ads.example.com", "doubleclick.net"].map { FNV1a.hash64($0) }
        try CompiledBlocklist.write(hashes: blocked, generation: 1, to: paths.blocklistURL)
        let allowed = ["safe.doubleclick.net"].map { FNV1a.hash64($0) }
        try CompiledBlocklist.write(hashes: allowed, generation: 1, to: paths.userAllowlistURL)
    }

    private func makeEngine(upstream: DNSUpstream,
                            configuration: DNSProxyEngine.Configuration = .init()) throws -> DNSProxyEngine {
        DNSProxyEngine(
            matcher: paths.loadMatcher(),
            upstream: upstream,
            logWriter: try QueryLogRingWriter(url: paths.queryLogURL, capacity: 1024),
            statsURL: paths.statsURL,
            configuration: configuration
        )
    }

    private func dnsPayload(from reply: Data) throws -> MiniDNSResponse {
        let udp = try XCTUnwrap(PacketParser.parseUDP(reply))
        return try XCTUnwrap(MiniDNSResponse(Data(udp.payload)))
    }

    func testBlockedDomainGetsZeroAnswerWithoutUpstream() async throws {
        let upstream = MockUpstream { _ in
            XCTFail("blocked queries must not reach the upstream")
            throw UpstreamError.timeout
        }
        let engine = try makeEngine(upstream: upstream)

        let query = makeDNSQueryData(id: 7, name: "ads.example.com")
        let reply = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](query))))

        let response = try dnsPayload(from: reply)
        XCTAssertEqual(response.id, 7)
        XCTAssertEqual(response.rcode, 0)
        XCTAssertEqual(response.answerType, DNSRecordType.a)
        XCTAssertEqual(response.answerRData, [0, 0, 0, 0])

        // Reply packet goes back to the querying socket.
        let udp = try XCTUnwrap(PacketParser.parseUDP(reply))
        XCTAssertEqual(udp.sourcePort, 53)
        XCTAssertEqual(udp.destinationAddress, [10, 0, 0, 5])
    }

    func testSubdomainOfBlockedDomainIsBlocked() async throws {
        let engine = try makeEngine(upstream: MockUpstream { _ in throw UpstreamError.timeout })
        let query = makeDNSQueryData(name: "x.tracking.doubleclick.net")
        let reply = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](query))))
        XCTAssertEqual(try dnsPayload(from: reply).answerRData, [0, 0, 0, 0])
    }

    func testBlockedHTTPSQueryGetsNoData() async throws {
        let engine = try makeEngine(upstream: MockUpstream { _ in throw UpstreamError.timeout })
        let query = makeDNSQueryData(name: "ads.example.com", qtype: DNSRecordType.https)
        let reply = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](query))))
        let response = try dnsPayload(from: reply)
        XCTAssertEqual(response.rcode, 0)
        XCTAssertEqual(response.ancount, 0)
    }

    func testBlockedAAAAOverIPv6() async throws {
        let engine = try makeEngine(upstream: MockUpstream { _ in throw UpstreamError.timeout })
        let query = makeDNSQueryData(name: "ads.example.com", qtype: DNSRecordType.aaaa)
        let reply = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV6(payload: [UInt8](query))))
        let response = try dnsPayload(from: reply)
        XCTAssertEqual(response.answerRData, [UInt8](repeating: 0, count: 16))
        XCTAssertEqual(PacketParser.parseUDP(reply)?.ipVersion, 6)
    }

    func testAllowedDomainIsForwarded() async throws {
        let upstream = MockUpstream { query in fakeAnswer(for: query) }
        let engine = try makeEngine(upstream: upstream)

        let query = makeDNSQueryData(id: 0x77, name: "www.apple.com")
        let reply = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](query))))
        let response = try dnsPayload(from: reply)
        XCTAssertTrue(response.isResponse)
        XCTAssertEqual(response.id, 0x77)
        XCTAssertEqual(response.questionName, "www.apple.com")
    }

    func testUserAllowBeatsBlocklist() async throws {
        let upstream = MockUpstream { query in fakeAnswer(for: query) }
        let engine = try makeEngine(upstream: upstream)
        let query = makeDNSQueryData(name: "cdn.safe.doubleclick.net")
        let reply = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](query))))
        // Forwarded (fake answer echo), not a 0.0.0.0 synthesis.
        XCTAssertNil(try dnsPayload(from: reply).answerRData)
    }

    func testUpstreamFailureYieldsServfail() async throws {
        let engine = try makeEngine(upstream: MockUpstream { _ in throw UpstreamError.timeout })
        let query = makeDNSQueryData(name: "www.apple.com")
        let reply = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](query))))
        XCTAssertEqual(try dnsPayload(from: reply).rcode, 2)
    }

    func testOversizeAnswerGetsTruncatedFlag() async throws {
        let upstream = MockUpstream { query in
            var big = [UInt8](fakeAnswer(for: query))
            big.append(contentsOf: [UInt8](repeating: 0, count: 2000))
            return Data(big)
        }
        let engine = try makeEngine(upstream: upstream)
        let query = makeDNSQueryData(name: "big.example.com")
        let reply = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](query))))
        XCTAssertTrue(try dnsPayload(from: reply).isTruncated)
    }

    func testNonDNSTrafficIsDropped() async throws {
        let engine = try makeEngine(upstream: MockUpstream { _ in throw UpstreamError.timeout })

        let toOtherPort = makeUDPPacketV4(destinationPort: 8080, payload: [1, 2, 3])
        let dropped = await engine.handlePacket(toOtherPort)
        XCTAssertNil(dropped)

        var tcp = [UInt8](makeUDPPacketV4(payload: [UInt8](makeDNSQueryData(name: "a.example.com"))))
        tcp[9] = 6
        let droppedTCP = await engine.handlePacket(Data(tcp))
        XCTAssertNil(droppedTCP)

        let garbage = await engine.handlePacket(Data([0xDE, 0xAD, 0xBE, 0xEF]))
        XCTAssertNil(garbage)
    }

    func testUnparseableDNSIsForwardedRaw() async throws {
        // A response-shaped message (QR=1) inside a UDP:53 packet: forwarded untouched.
        var responseLike = [UInt8](makeDNSQueryData(name: "weird.example.com"))
        responseLike[2] |= 0x80

        let upstream = MockUpstream { query in
            XCTAssertEqual([UInt8](query), responseLike)
            return fakeAnswer(for: query)
        }
        let engine = try makeEngine(upstream: upstream)
        let reply = await engine.handlePacket(makeUDPPacketV4(payload: responseLike))
        XCTAssertNotNil(reply)
    }

    func testStatsAndLogAreRecorded() async throws {
        let upstream = MockUpstream { query in fakeAnswer(for: query) }
        let engine = try makeEngine(upstream: upstream)

        _ = await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](makeDNSQueryData(name: "ads.example.com"))))
        _ = await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](makeDNSQueryData(name: "www.apple.com"))))
        _ = await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](makeDNSQueryData(name: "doubleclick.net"))))
        await engine.flush()

        let stats = await engine.statsSnapshot()
        XCTAssertEqual(stats.totalQueries, 3)
        XCTAssertEqual(stats.blockedQueries, 2)
        XCTAssertEqual(stats.blocklistEntryCount, 2)
        XCTAssertNotNil(stats.startedAt)

        let persisted = StatsPersistence.load(from: paths.statsURL)
        XCTAssertEqual(persisted.totalQueries, 3)
        XCTAssertEqual(persisted.totalBlocked, 2)

        let (records, _) = QueryLogRingReader(url: paths.queryLogURL).read(since: 0)
        XCTAssertEqual(records.count, 3)
        XCTAssertEqual(records.filter { $0.verdict == .blocked }.map(\.domain).sorted(),
                       ["ads.example.com", "doubleclick.net"])
    }

    func testReloadPicksUpNewRules() async throws {
        let upstream = MockUpstream { query in fakeAnswer(for: query) }
        let engine = try makeEngine(upstream: upstream)

        let name = "fresh.example.org"
        let packet = makeUDPPacketV4(payload: [UInt8](makeDNSQueryData(name: name)))
        let before = try XCTUnwrap(await engine.handlePacket(packet))
        XCTAssertNil(try dnsPayload(from: before).answerRData)  // forwarded

        try CompiledBlocklist.write(hashes: [FNV1a.hash64(name)], generation: 2, to: paths.blocklistURL)
        await engine.reload(matcher: paths.loadMatcher())
        let generation = await engine.blocklistGeneration
        XCTAssertEqual(generation, 2)

        let after = try XCTUnwrap(await engine.handlePacket(packet))
        XCTAssertEqual(try dnsPayload(from: after).answerRData, [0, 0, 0, 0])  // now blocked
    }

    func testInFlightLimitYieldsServfail() async throws {
        let upstream = MockUpstream { query in
            try await Task.sleep(nanoseconds: 200_000_000)
            return fakeAnswer(for: query)
        }
        let engine = try makeEngine(
            upstream: upstream,
            configuration: .init(maxInFlight: 1)
        )

        async let first = engine.handlePacket(makeUDPPacketV4(payload: [UInt8](makeDNSQueryData(id: 1, name: "one.example.com"))))
        try await Task.sleep(nanoseconds: 50_000_000)
        let second = try XCTUnwrap(await engine.handlePacket(makeUDPPacketV4(payload: [UInt8](makeDNSQueryData(id: 2, name: "two.example.com"))))
        )
        XCTAssertEqual(try dnsPayload(from: second).rcode, 2)
        _ = await first
    }
}
