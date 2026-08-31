import XCTest
@testable import IBlockerKit

final class MobileConfigBuilderTests: XCTestCase {

    func testAdGuardProfileStructure() throws {
        let data = MobileConfigBuilder.profile(for: .adguard)
        let plist = try PropertyListSerialization.propertyList(from: data, format: nil)
        let root = try XCTUnwrap(plist as? [String: Any])

        XCTAssertEqual(root["PayloadType"] as? String, "Configuration")
        XCTAssertEqual(root["PayloadVersion"] as? Int, 1)
        XCTAssertEqual(root["PayloadIdentifier"] as? String, "com.adblocker.profile.adguard")

        let contents = try XCTUnwrap(root["PayloadContent"] as? [[String: Any]])
        XCTAssertEqual(contents.count, 1)
        let payload = contents[0]
        XCTAssertEqual(payload["PayloadType"] as? String, "com.apple.dnsSettings.managed")

        let dns = try XCTUnwrap(payload["DNSSettings"] as? [String: Any])
        XCTAssertEqual(dns["DNSProtocol"] as? String, "HTTPS")
        XCTAssertEqual(dns["ServerURL"] as? String, "https://dns.adguard-dns.com/dns-query")
        XCTAssertEqual((dns["ServerAddresses"] as? [String])?.count, 4)
    }

    func testProfileIsDeterministic() {
        XCTAssertEqual(MobileConfigBuilder.profile(for: .adguard), MobileConfigBuilder.profile(for: .adguard))
        XCTAssertNotEqual(MobileConfigBuilder.profile(for: .adguard), MobileConfigBuilder.profile(for: .quad9))
    }

    func testStableUUIDFormat() {
        let uuid = MobileConfigBuilder.stableUUID("payload.test")
        XCTAssertEqual(uuid, MobileConfigBuilder.stableUUID("payload.test"))
        XCTAssertNotEqual(uuid, MobileConfigBuilder.stableUUID("payload.other"))
        XCTAssertNotNil(UUID(uuidString: uuid), "must be a parseable UUID: \(uuid)")
    }

    func testNextDNSProfileUsesConfigID() throws {
        let preset = DNSProviderPreset.nextDNS(configID: " abc123 ")
        let data = MobileConfigBuilder.profile(for: preset)
        let plist = try PropertyListSerialization.propertyList(from: data, format: nil)
        let root = try XCTUnwrap(plist as? [String: Any])
        let payload = try XCTUnwrap((root["PayloadContent"] as? [[String: Any]])?.first)
        let dns = try XCTUnwrap(payload["DNSSettings"] as? [String: Any])
        XCTAssertEqual(dns["ServerURL"] as? String, "https://dns.nextdns.io/abc123")
        XCTAssertNil(dns["ServerAddresses"])
    }

    func testEscaping() {
        XCTAssertEqual(MobileConfigBuilder.xmlEscape("a<b>&c"), "a&lt;b&gt;&amp;c")
    }

    func testEveryPresetProducesParseablePlist() throws {
        for preset in DNSProviderPreset.all {
            let data = MobileConfigBuilder.profile(for: preset)
            XCTAssertNoThrow(try PropertyListSerialization.propertyList(from: data, format: nil),
                             "preset \(preset.id) generated invalid plist")
        }
    }
}
