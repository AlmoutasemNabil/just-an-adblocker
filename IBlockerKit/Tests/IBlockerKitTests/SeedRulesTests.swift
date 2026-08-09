import XCTest
@testable import IBlockerKit

/// The acceptance measure: in-app Google ads must be blocked — even on a
/// fresh install with zero downloaded lists.
final class SeedRulesTests: XCTestCase {

    func testSeedTextParsesCleanly() {
        let parsed = FilterListParser.parse(SeedRules.text)
        XCTAssertEqual(parsed.skippedLines, 0, "typo in SeedRules.text")
        XCTAssertTrue(parsed.allowDomains.isEmpty)
        XCTAssertEqual(parsed.blockDomains.count, 17)
        XCTAssertTrue(parsed.blockDomains.contains("doubleclick.net"))
        XCTAssertTrue(parsed.blockDomains.contains("googlesyndication.com"))
        XCTAssertTrue(parsed.blockDomains.contains("app-measurement.com"))
    }

    func testBundledSourceIsBuiltInAndEnabled() {
        let source = FilterListSource.builtIn.first
        XCTAssertEqual(source?.id, SeedRules.sourceID)
        XCTAssertEqual(source?.enabled, true)
        XCTAssertEqual(source?.isBuiltIn, true)

        // Fresh state carries it; older saved states gain it via the merge.
        let fresh = FilterListState()
        XCTAssertTrue(fresh.sources.contains { $0.id == SeedRules.sourceID && $0.enabled })
    }

    func testCompileWithZeroDownloadsBlocksGoogleInAppAds() async throws {
        let paths = AppGroupPaths(containerURL: try makeTempDirectory())
        try paths.ensureDirectories()

        // Fresh install, every download failing: only the bundled core rules.
        var state = FilterListState()
        let updater = FilterListUpdater(paths: paths) { _ in
            FetchResult(statusCode: 503, body: Data())
        }
        let summary = await updater.update(state: &state)
        XCTAssertTrue(summary.updatedSourceIDs.isEmpty)
        // The bundled source is never fetched, so it cannot fail.
        XCTAssertNil(summary.failedSourceIDs[SeedRules.sourceID])

        let stats = try BlocklistCompiler.compile(state: &state, paths: paths)
        XCTAssertGreaterThanOrEqual(stats.blockedEntryCount, 17)
        XCTAssertEqual(state.metadata[SeedRules.sourceID]?.entryCount, 17)

        let matcher = paths.loadMatcher()

        // The canonical AdMob request path — all must be blocked.
        XCTAssertEqual(matcher.verdict(for: "googleads.g.doubleclick.net"), .block)
        XCTAssertEqual(matcher.verdict(for: "pagead2.googlesyndication.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "tpc.googlesyndication.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "app-measurement.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "x.app-measurement.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "adservice.google.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "admob.com"), .block)
        XCTAssertEqual(matcher.verdict(for: "d.applovin.com"), .block)

        // No overblocking of the surrounding legitimate domains.
        XCTAssertEqual(matcher.verdict(for: "google.com"), .none)
        XCTAssertEqual(matcher.verdict(for: "www.google.com"), .none)
        XCTAssertEqual(matcher.verdict(for: "apple.com"), .none)
        XCTAssertEqual(matcher.verdict(for: "unity3d.com"), .none)
    }

    func testDisablingBundledSourceRemovesSeedRules() throws {
        let paths = AppGroupPaths(containerURL: try makeTempDirectory())
        try paths.ensureDirectories()

        var state = FilterListState()
        if let index = state.sources.firstIndex(where: { $0.id == SeedRules.sourceID }) {
            state.sources[index].enabled = false
        }
        _ = try BlocklistCompiler.compile(state: &state, paths: paths)
        let matcher = paths.loadMatcher()
        XCTAssertEqual(matcher.verdict(for: "googleads.g.doubleclick.net"), .none)
    }
}
