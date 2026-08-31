import XCTest
@testable import IBlockerKit

final class CompilerAndMatcherTests: XCTestCase {

    private func makePaths() throws -> AppGroupPaths {
        AppGroupPaths(containerURL: try makeTempDirectory())
    }

    func testCompileAndMatchEndToEnd() throws {
        let paths = try makePaths()
        try paths.ensureDirectories()

        var state = FilterListState()
        state.sources = [
            FilterListSource(id: "test-a", name: "A", url: URL(string: "https://example.com/a")!, enabled: true, isBuiltIn: false),
            FilterListSource(id: "test-b", name: "B", url: URL(string: "https://example.com/b")!, enabled: true, isBuiltIn: false),
            FilterListSource(id: "test-off", name: "Off", url: URL(string: "https://example.com/c")!, enabled: false, isBuiltIn: false),
        ]
        state.userAllowlist = ["safe.doubleclick.net"]
        state.userDenylist = ["mytracker.example.com"]

        try """
        0.0.0.0 ads.example.com
        ||doubleclick.net^
        @@||okay.example.com^
        okay.example.com
        """.write(to: paths.cachedListURL(sourceID: "test-a"), atomically: true, encoding: .utf8)

        try """
        tracker.io
        """.write(to: paths.cachedListURL(sourceID: "test-b"), atomically: true, encoding: .utf8)

        try """
        disabled.example.com
        """.write(to: paths.cachedListURL(sourceID: "test-off"), atomically: true, encoding: .utf8)

        let stats = try BlocklistCompiler.compile(state: &state, paths: paths)
        XCTAssertEqual(stats.generation, 1)
        // ads.example.com, doubleclick.net, tracker.io (okay.example.com subtracted by @@)
        XCTAssertEqual(stats.blockedEntryCount, 3)
        XCTAssertEqual(stats.userAllowEntryCount, 1)
        XCTAssertEqual(stats.userDenyEntryCount, 1)

        let matcher = paths.loadMatcher()
        XCTAssertEqual(matcher.blockedEntryCount, 3)

        // Exact and subdomain blocking.
        XCTAssertEqual(matcher.verdict(for: "ads.example.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "sub.ads.example.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "doubleclick.net"), .block)
        XCTAssertEqual(matcher.verdict(for: "x.y.doubleclick.net"), .block)
        XCTAssertEqual(matcher.verdict(for: "tracker.io"), .block)

        // List-level @@ subtraction.
        XCTAssertEqual(matcher.verdict(for: "okay.example.com"), .none)

        // Disabled source not compiled.
        XCTAssertEqual(matcher.verdict(for: "disabled.example.com"), .none)

        // User allow beats the blocklist, including for subdomains.
        XCTAssertEqual(matcher.verdict(for: "safe.doubleclick.net"), .allow)
        XCTAssertEqual(matcher.verdict(for: "deep.safe.doubleclick.net"), .allow)

        // User deny blocks unlisted domains.
        XCTAssertEqual(matcher.verdict(for: "mytracker.example.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "cdn.mytracker.example.com"), .block)

        // Unrelated domains and TLD-only names never match.
        XCTAssertEqual(matcher.verdict(for: "www.apple.com"), .none)
        XCTAssertEqual(matcher.verdict(for: "com"), .none)
        XCTAssertEqual(matcher.verdict(for: "example.com"), .none)

        // Second compile bumps the generation.
        let second = try BlocklistCompiler.compile(state: &state, paths: paths)
        XCTAssertEqual(second.generation, 2)
        XCTAssertEqual(try CompiledBlocklistView(contentsOf: paths.blocklistURL).generation, 2)
    }

    func testEmptyBlocklistMatchesNothing() throws {
        let paths = try makePaths()
        try CompiledBlocklist.write(hashes: [UInt64](), generation: 1, to: paths.blocklistURL)
        let view = try CompiledBlocklistView(contentsOf: paths.blocklistURL)
        XCTAssertTrue(view.isEmpty)
        let matcher = DomainMatcher(blocklist: view)
        XCTAssertEqual(matcher.verdict(for: "anything.example.com"), .none)
    }

    func testCorruptBlobIsRejected() throws {
        let paths = try makePaths()
        try Data("garbage".utf8).write(to: paths.blocklistURL)
        XCTAssertThrowsError(try CompiledBlocklistView(contentsOf: paths.blocklistURL))

        var valid = CompiledBlocklist.serialize(hashes: [1, 2, 3], generation: 1)
        valid.replaceSubrange(0..<4, with: Data("XXXX".utf8))
        try valid.write(to: paths.blocklistURL)
        XCTAssertThrowsError(try CompiledBlocklistView(contentsOf: paths.blocklistURL))
    }

    func testPropertyFiftyThousandDomains() throws {
        let paths = try makePaths()
        var generator = SeededGenerator(seed: 0xB10C4E12)

        var blocked = Set<String>()
        var notBlocked = Set<String>()
        while blocked.count < 25_000 {
            blocked.insert("h\(generator.next() % 1_000_000_000).blocked-\(generator.next() % 10_000).example")
        }
        while notBlocked.count < 25_000 {
            notBlocked.insert("h\(generator.next() % 1_000_000_000).clean-\(generator.next() % 10_000).example")
        }
        notBlocked.subtract(blocked)

        let hashes = blocked.map { FNV1a.hash64($0) }
        try CompiledBlocklist.write(hashes: hashes, generation: 7, to: paths.blocklistURL)
        let matcher = DomainMatcher(blocklist: try CompiledBlocklistView(contentsOf: paths.blocklistURL))

        for domain in blocked {
            XCTAssertEqual(matcher.verdict(for: domain), .block, "false negative for \(domain)")
            XCTAssertEqual(matcher.verdict(for: "www." + domain), .block, "subdomain miss for \(domain)")
        }
        var falsePositives = 0
        for domain in notBlocked where matcher.verdict(for: domain) == .block {
            falsePositives += 1
        }
        // 64-bit hashes: collisions across 50k domains are effectively impossible.
        XCTAssertEqual(falsePositives, 0)
    }

    func testSerializeIsSortedAndDeduplicated() {
        let data = CompiledBlocklist.serialize(hashes: [9, 3, 3, 7, 1], generation: 5)
        let view = try! writeAndOpen(data)
        XCTAssertEqual(view.count, 4)
        XCTAssertEqual(view.generation, 5)
        XCTAssertTrue(view.contains(1))
        XCTAssertTrue(view.contains(3))
        XCTAssertTrue(view.contains(7))
        XCTAssertTrue(view.contains(9))
        XCTAssertFalse(view.contains(2))
        XCTAssertFalse(view.contains(0))
        XCTAssertFalse(view.contains(UInt64.max))
    }

    private func writeAndOpen(_ data: Data) throws -> CompiledBlocklistView {
        let url = try makeTempDirectory().appendingPathComponent("blob.bin")
        try data.write(to: url)
        return try CompiledBlocklistView(contentsOf: url)
    }
}
