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

    func testRelayTextParsesCleanly() {
        let parsed = FilterListParser.parse(SeedRules.relayText)
        XCTAssertEqual(parsed.skippedLines, 0, "typo in SeedRules.relayText")
        XCTAssertEqual(parsed.blockDomains.count, 8)
        XCTAssertTrue(parsed.blockDomains.contains("mask.icloud.com"))
        // The CNAME target iOS actually connects to — without it, blocking
        // mask.icloud.com alone can be sidestepped.
        XCTAssertTrue(parsed.blockDomains.contains("mask.apple-dns.net"))
        XCTAssertTrue(parsed.blockDomains.contains("apple-relay.fastly-edge.com"))
    }

    func testTunnelFallbackBlocksWithNoBlobsOnDisk() throws {
        // The scenario that leaked in-app ads: tunnel running against a
        // missing/stale blob. The in-memory fallback must hold the floor.
        let paths = AppGroupPaths(containerURL: try makeTempDirectory())
        let fallback = SeedRules.fallbackHashes(state: FilterListState())
        let matcher = paths.loadMatcher(builtInBlockHashes: fallback)

        XCTAssertEqual(matcher.verdict(for: "googleads.g.doubleclick.net"), .block)
        XCTAssertEqual(matcher.verdict(for: "pagead2.googlesyndication.com"), .block)
        // Apple's relay is never part of the floor while it is suppressed.
        XCTAssertEqual(matcher.verdict(for: "mask.icloud.com"), .none)
        XCTAssertEqual(matcher.verdict(for: "apple-relay.fastly-edge.com"), .none)
        XCTAssertEqual(matcher.verdict(for: "apple.com"), .none)
        XCTAssertEqual(matcher.verdict(for: "icloud.com"), .none)
        XCTAssertGreaterThan(matcher.blockedEntryCount, 0)

        // Without the fallback, nothing matches — the old failure mode.
        XCTAssertEqual(paths.loadMatcher().verdict(for: "googleads.g.doubleclick.net"), .none)
    }

    func testFallbackRespectsDisabledBundledSources() {
        var state = FilterListState()
        if let index = state.sources.firstIndex(where: { $0.id == SeedRules.relaySourceID }) {
            state.sources[index].enabled = false
        }
        let fallback = SeedRules.fallbackHashes(state: state)
        XCTAssertFalse(fallback.contains(FNV1a.hash64("mask.icloud.com")))
        XCTAssertTrue(fallback.contains(FNV1a.hash64("doubleclick.net")))

        // Sources missing from saved state (older builds) count as enabled.
        state.sources.removeAll { $0.id == SeedRules.sourceID }
        XCTAssertTrue(SeedRules.fallbackHashes(state: state).contains(FNV1a.hash64("doubleclick.net")))

        // A suppressed source stays out even under that same default.
        state.sources.removeAll { $0.id == SeedRules.relaySourceID }
        XCTAssertEqual(
            SeedRules.fallbackHashes(state: state).contains(FNV1a.hash64("mask.icloud.com")),
            FeatureFlags.showAppleRelayControls
        )
    }

    func testUserAllowBeatsFallback() throws {
        let paths = AppGroupPaths(containerURL: try makeTempDirectory())
        try CompiledBlocklist.write(hashes: [FNV1a.hash64("doubleclick.net")], generation: 1,
                                    to: paths.userAllowlistURL)
        let matcher = paths.loadMatcher(builtInBlockHashes: SeedRules.fallbackHashes(state: FilterListState()))
        XCTAssertEqual(matcher.verdict(for: "googleads.g.doubleclick.net"), .allow)
        // Relay rules ship disabled and suppressed — the fallback leaves them out.
        XCTAssertEqual(matcher.verdict(for: "mask.icloud.com"), .none)
    }

    func testCompileStampsSeedVersion() throws {
        let paths = AppGroupPaths(containerURL: try makeTempDirectory())
        try paths.ensureDirectories()
        var state = FilterListState()
        XCTAssertNil(state.compiledSeedVersion)
        _ = try BlocklistCompiler.compile(state: &state, paths: paths)
        XCTAssertEqual(state.compiledSeedVersion, SeedRules.version)

        // The relay ruleset is not compiled in while it is suppressed.
        let matcher = paths.loadMatcher()
        XCTAssertEqual(matcher.verdict(for: "mask.icloud.com"), .none)
        XCTAssertEqual(matcher.verdict(for: "googleads.g.doubleclick.net"), .block)
    }

    func testLegacyStateWithoutSeedVersionDecodes() throws {
        let legacyJSON = """
        {"sources":[],"metadata":{},"userAllowlist":[],"userDenylist":[],"generation":9}
        """
        let state = try JSONDecoder().decode(FilterListState.self, from: Data(legacyJSON.utf8))
        XCTAssertNil(state.compiledSeedVersion)
        XCTAssertEqual(state.generation, 9)
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
