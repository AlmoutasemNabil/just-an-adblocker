import Foundation

/// Canonical locations of every file shared between the app and its
/// extensions inside the App Group container.
public struct AppGroupPaths: Sendable {
    public let containerURL: URL

    public init(containerURL: URL) {
        self.containerURL = containerURL
    }

    #if canImport(Darwin)
    /// Resolves the real App Group container. The group ID is read from the
    /// bundle's `AppGroupID` Info.plist key by callers, so no target ever
    /// hardcodes it.
    public init?(groupID: String) {
        guard let url = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: groupID) else { return nil }
        self.init(containerURL: url)
    }

    /// Group ID as stamped into Info.plist at build time from the
    /// APP_GROUP_ID build setting.
    public static func groupID(from bundle: Bundle) -> String? {
        bundle.object(forInfoDictionaryKey: "AppGroupID") as? String
    }
    #endif

    public var blocklistURL: URL { containerURL.appendingPathComponent("blocklist.bin") }
    public var userAllowlistURL: URL { containerURL.appendingPathComponent("allowlist.bin") }
    public var userDenylistURL: URL { containerURL.appendingPathComponent("denylist.bin") }
    public var queryLogURL: URL { containerURL.appendingPathComponent("querylog.ring") }
    public var statsURL: URL { containerURL.appendingPathComponent("stats.json") }
    public var filterStateURL: URL { containerURL.appendingPathComponent("sources.json") }
    public var listsCacheDirectory: URL { containerURL.appendingPathComponent("lists", isDirectory: true) }
    public var contentBlockerJSONURL: URL { containerURL.appendingPathComponent("contentblocker.json") }

    public func cachedListURL(sourceID: String) -> URL {
        listsCacheDirectory.appendingPathComponent("\(sourceID).txt")
    }

    public func ensureDirectories() throws {
        try FileManager.default.createDirectory(at: listsCacheDirectory,
                                                withIntermediateDirectories: true)
    }

    /// Loads the current matcher from disk; missing files yield an empty
    /// matcher rather than an error so the tunnel can start before the first
    /// list download completes. Pass `builtInBlockHashes` (the seed
    /// fallback) so blocking never depends solely on the on-disk blob.
    public func loadMatcher(builtInBlockHashes: Set<UInt64> = []) -> DomainMatcher {
        DomainMatcher(
            blocklist: try? CompiledBlocklistView(contentsOf: blocklistURL),
            userAllowlist: try? CompiledBlocklistView(contentsOf: userAllowlistURL),
            userDenylist: try? CompiledBlocklistView(contentsOf: userDenylistURL),
            builtInBlockHashes: builtInBlockHashes
        )
    }
}
