import XCTest
@testable import IBlockerKit

final class SharedSettingsTests: XCTestCase {

    private func makeSettings() -> (SharedSettings, String) {
        let suite = "iblocker-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        return (SharedSettings(defaults: defaults), suite)
    }

    func testRelayStrategyDefaultsToBlockDomains() {
        let (settings, suite) = makeSettings()
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        XCTAssertEqual(settings.relayStrategy, .blockDomains)
    }

    func testRelayStrategyRoundTrip() {
        let (settings, suite) = makeSettings()
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

        settings.relayStrategy = .autoSuspend
        XCTAssertEqual(settings.relayStrategy, .autoSuspend)
        settings.relayStrategy = .keepRelay
        XCTAssertEqual(settings.relayStrategy, .keepRelay)
        settings.relayStrategy = .blockDomains
        XCTAssertEqual(settings.relayStrategy, .blockDomains)
    }

    /// Storage round-trips whatever it is given, but the tunnel must not act on
    /// a stored choice the user has no UI to change back.
    func testEffectiveRelayStrategyIgnoresStoredValueWhileControlsHidden() throws {
        try XCTSkipIf(FeatureFlags.showAppleRelayControls, "Relay controls are visible")
        let (settings, suite) = makeSettings()
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

        settings.relayStrategy = .autoSuspend
        XCTAssertEqual(settings.relayStrategy, .autoSuspend)
        XCTAssertEqual(settings.effectiveRelayStrategy, .blockDomains)
    }

    func testUpstreamConfigRoundTrip() {
        let (settings, suite) = makeSettings()
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }

        XCTAssertEqual(settings.upstreamConfig, .default)
        let custom = UpstreamConfig(kind: .udp, udpAddress: "9.9.9.9")
        settings.upstreamConfig = custom
        XCTAssertEqual(settings.upstreamConfig, custom)
    }
}
