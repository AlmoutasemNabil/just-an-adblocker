import Foundation

/// A DNS blocklist source. Built-ins ship with the app; users can add any
/// URL serving hosts/domain/AdGuard-format text.
public struct FilterListSource: Codable, Identifiable, Sendable, Equatable {
    public var id: String
    public var name: String
    public var url: URL
    public var enabled: Bool
    public var isBuiltIn: Bool

    public init(id: String, name: String, url: URL, enabled: Bool, isBuiltIn: Bool) {
        self.id = id
        self.name = name
        self.url = url
        self.enabled = enabled
        self.isBuiltIn = isBuiltIn
    }

    /// Only OISD starts enabled: broad ad/tracker coverage with a strict
    /// no-breakage policy. The others are one tap away.
    public static let builtIn: [FilterListSource] = [
        FilterListSource(
            id: "oisd-big",
            name: "OISD Big",
            url: URL(string: "https://big.oisd.nl")!,
            enabled: true,
            isBuiltIn: true
        ),
        FilterListSource(
            id: "hagezi-pro",
            name: "HaGeZi Multi Pro",
            url: URL(string: "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt")!,
            enabled: false,
            isBuiltIn: true
        ),
        FilterListSource(
            id: "stevenblack",
            name: "StevenBlack Hosts",
            url: URL(string: "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts")!,
            enabled: false,
            isBuiltIn: true
        ),
        FilterListSource(
            id: "adguard-dns",
            name: "AdGuard DNS Filter",
            url: URL(string: "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt")!,
            enabled: false,
            isBuiltIn: true
        ),
    ]
}

public struct FilterListMetadata: Codable, Sendable, Equatable {
    public var etag: String?
    public var lastModified: String?
    public var lastFetched: Date?
    public var entryCount: Int
    public var lastError: String?

    public init(etag: String? = nil, lastModified: String? = nil, lastFetched: Date? = nil,
                entryCount: Int = 0, lastError: String? = nil) {
        self.etag = etag
        self.lastModified = lastModified
        self.lastFetched = lastFetched
        self.entryCount = entryCount
        self.lastError = lastError
    }
}

/// Everything the Lists feature persists, as one JSON document in the
/// App Group (sources.json).
public struct FilterListState: Codable, Sendable, Equatable {
    public var sources: [FilterListSource]
    public var metadata: [String: FilterListMetadata]
    public var userAllowlist: [String]
    public var userDenylist: [String]
    public var generation: UInt32

    public init(sources: [FilterListSource] = FilterListSource.builtIn,
                metadata: [String: FilterListMetadata] = [:],
                userAllowlist: [String] = [],
                userDenylist: [String] = [],
                generation: UInt32 = 0) {
        self.sources = sources
        self.metadata = metadata
        self.userAllowlist = userAllowlist
        self.userDenylist = userDenylist
        self.generation = generation
    }

    public static func load(from url: URL) -> FilterListState {
        guard let data = try? Data(contentsOf: url),
              var state = try? JSONDecoder().decode(FilterListState.self, from: data) else {
            return FilterListState()
        }
        // Merge in any built-ins added by app updates.
        let known = Set(state.sources.map(\.id))
        for builtIn in FilterListSource.builtIn where !known.contains(builtIn.id) {
            state.sources.append(builtIn)
        }
        return state
    }

    public func save(to url: URL) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(self).write(to: url, options: .atomic)
    }
}
