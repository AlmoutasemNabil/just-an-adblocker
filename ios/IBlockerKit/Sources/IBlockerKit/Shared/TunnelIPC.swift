import Foundation

/// Upstream resolver configuration, stored in shared defaults and passed to
/// the tunnel over IPC.
public struct UpstreamConfig: Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable {
        case doh
        case udp
    }

    public var kind: Kind
    /// DoH endpoint. Presets use IP-literal URLs (https://94.140.14.14/dns-query)
    /// because the extension's own hostname lookups would recurse through the
    /// tunnel it is serving.
    public var dohURL: String?
    /// Plain-DNS server IP for UDP upstreams.
    public var udpAddress: String?

    public init(kind: Kind, dohURL: String? = nil, udpAddress: String? = nil) {
        self.kind = kind
        self.dohURL = dohURL
        self.udpAddress = udpAddress
    }

    /// Cloudflare via IP-literal DoH: fast, neutral, no bootstrap lookup.
    public static let `default` = UpstreamConfig(kind: .doh, dohURL: "https://1.1.1.1/dns-query")

    public var displayName: String {
        switch kind {
        case .doh: return dohURL ?? "DoH"
        case .udp: return "\(udpAddress ?? "?"):53 (UDP)"
        }
    }
}

/// Requests the app sends to the tunnel via sendProviderMessage.
public enum TunnelRequest: Codable, Sendable {
    case ping
    case reloadRules
    case getStats
    case setUpstream(UpstreamConfig)
    /// Suspend blocking until the given instant; nil resumes.
    case setPause(until: Date?)
}

public struct TunnelRuntimeStats: Codable, Sendable, Equatable {
    public var startedAt: Date?
    public var totalQueries: UInt64
    public var blockedQueries: UInt64
    public var blocklistEntryCount: UInt64
    public var memoryBytes: UInt64?
    /// Non-nil when blocking is currently paused; the instant it resumes.
    public var pausedUntil: Date?

    public init(startedAt: Date? = nil, totalQueries: UInt64 = 0, blockedQueries: UInt64 = 0,
                blocklistEntryCount: UInt64 = 0, memoryBytes: UInt64? = nil, pausedUntil: Date? = nil) {
        self.startedAt = startedAt
        self.totalQueries = totalQueries
        self.blockedQueries = blockedQueries
        self.blocklistEntryCount = blocklistEntryCount
        self.memoryBytes = memoryBytes
        self.pausedUntil = pausedUntil
    }
}

public enum TunnelResponse: Codable, Sendable {
    case ok
    case stats(TunnelRuntimeStats)
    case failure(String)
}

public enum TunnelIPCCoder {
    public static func encode(_ request: TunnelRequest) throws -> Data {
        try JSONEncoder().encode(request)
    }
    public static func decodeRequest(_ data: Data) throws -> TunnelRequest {
        try JSONDecoder().decode(TunnelRequest.self, from: data)
    }
    public static func encode(_ response: TunnelResponse) throws -> Data {
        try JSONEncoder().encode(response)
    }
    public static func decodeResponse(_ data: Data) throws -> TunnelResponse {
        try JSONDecoder().decode(TunnelResponse.self, from: data)
    }
}
