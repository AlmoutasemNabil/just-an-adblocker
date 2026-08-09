import Foundation

/// Typed accessors over the App Group's shared UserDefaults.
/// UserDefaults is documented thread-safe, hence the unchecked conformance.
public struct SharedSettings: @unchecked Sendable {
    public enum Keys {
        public static let upstreamConfig = "upstreamConfig"
        public static let onboardingComplete = "onboardingComplete"
        public static let queryLogEnabled = "queryLogEnabled"
        public static let lastListUpdate = "lastListUpdate"
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
}
