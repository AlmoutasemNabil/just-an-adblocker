import Foundation

/// How the tunnel deals with Apple's relay (Private Relay + "Limit IP
/// Address Tracking"), which can carry tracker traffic past any DNS filter.
public enum RelayStrategy: String, CaseIterable, Sendable {
    /// DNS-block the relay endpoints (strongest; Private Relay unavailable
    /// while protection is on).
    case blockDomains
    /// Touch nothing Apple; the user turns off "Limit IP Address Tracking"
    /// per network instead. Private Relay keeps working.
    case keepRelay
    /// Present the tunnel as a full VPN so iOS suspends the relay by itself
    /// while protection runs — the approach commercial blockers use. The
    /// relay returns automatically when protection stops.
    case autoSuspend
}

/// Typed accessors over the App Group's shared UserDefaults.
/// UserDefaults is documented thread-safe, hence the unchecked conformance.
public struct SharedSettings: @unchecked Sendable {
    public enum Keys {
        public static let upstreamConfig = "upstreamConfig"
        public static let onboardingComplete = "onboardingComplete"
        public static let queryLogEnabled = "queryLogEnabled"
        public static let lastListUpdate = "lastListUpdate"
        public static let relayStrategy = "relayStrategy"
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    #if canImport(Darwin)
    public init?(groupID: String) {
        guard let defaults = UserDefaults(suiteName: groupID) else { return nil }
        self.init(defaults: defaults)
    }
    #endif

    public var upstreamConfig: UpstreamConfig {
        get {
            guard let data = defaults.data(forKey: Keys.upstreamConfig),
                  let config = try? JSONDecoder().decode(UpstreamConfig.self, from: data) else {
                return .default
            }
            return config
        }
        nonmutating set {
            if let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: Keys.upstreamConfig)
            }
        }
    }

    public var onboardingComplete: Bool {
        get { defaults.bool(forKey: Keys.onboardingComplete) }
        nonmutating set { defaults.set(newValue, forKey: Keys.onboardingComplete) }
    }

    public var queryLogEnabled: Bool {
        get { defaults.object(forKey: Keys.queryLogEnabled) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Keys.queryLogEnabled) }
    }

    public var lastListUpdate: Date? {
        get { defaults.object(forKey: Keys.lastListUpdate) as? Date }
        nonmutating set { defaults.set(newValue, forKey: Keys.lastListUpdate) }
    }

    public var relayStrategy: RelayStrategy {
        get {
            guard let raw = defaults.string(forKey: Keys.relayStrategy),
                  let strategy = RelayStrategy(rawValue: raw) else { return .blockDomains }
            return strategy
        }
        nonmutating set { defaults.set(newValue.rawValue, forKey: Keys.relayStrategy) }
    }
}
