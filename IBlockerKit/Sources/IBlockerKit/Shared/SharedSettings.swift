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
        public static let pausedUntil = "pausedUntil"
        public static let protectionActive = "protectionActive"
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

    /// When set to a future instant, the tunnel forwards everything without
    /// blocking (a temporary "let this through" for a broken checkout,
    /// captcha, or link). Any process — app, widget, App Intent — can write
    /// it; the tunnel re-reads it on its maintenance tick.
    public var pausedUntil: Date? {
        get {
            let t = defaults.double(forKey: Keys.pausedUntil)
            guard t > 0 else { return nil }
            let date = Date(timeIntervalSince1970: t)
            return date > Date() ? date : nil
        }
        nonmutating set {
            defaults.set(newValue?.timeIntervalSince1970 ?? 0, forKey: Keys.pausedUntil)
        }
    }

    /// Mirror of the tunnel's connected state, written by the provider and
    /// the VPN control helper so widgets and the Control Center toggle can
    /// show the right state without loading the VPN manager.
    public var protectionActive: Bool {
        get { defaults.bool(forKey: Keys.protectionActive) }
        nonmutating set { defaults.set(newValue, forKey: Keys.protectionActive) }
    }
}
