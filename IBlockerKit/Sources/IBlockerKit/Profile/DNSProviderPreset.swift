import Foundation

/// Encrypted-DNS providers offered for the .mobileconfig profile export and
/// (where an IP-literal DoH endpoint exists) for the tunnel's own upstream.
public struct DNSProviderPreset: Identifiable, Sendable, Equatable {
    public enum Transport: String, Sendable {
        case https  // DNS over HTTPS
        case tls    // DNS over TLS
    }

    public let id: String
    public let name: String
    public let detail: String
    public let transport: Transport
    /// DoH endpoint URL (transport == .https).
    public let serverURL: String?
    /// DoT hostname (transport == .tls).
    public let serverName: String?
    /// Resolver IPs, used by the OS to bootstrap the encrypted connection.
    public let addresses: [String]
    /// IP-literal DoH URL usable from inside the packet tunnel, where
    /// hostname resolution would recurse. Nil when the provider's certificates
    /// don't cover bare IPs.
    public let tunnelDoHURL: String?
    /// True when the provider filters ads itself (relevant for profile mode,
    /// where all blocking happens upstream).
    public let blocksAds: Bool

    public init(id: String, name: String, detail: String, transport: Transport,
                serverURL: String? = nil, serverName: String? = nil,
                addresses: [String], tunnelDoHURL: String? = nil, blocksAds: Bool) {
        self.id = id
        self.name = name
        self.detail = detail
        self.transport = transport
        self.serverURL = serverURL
        self.serverName = serverName
        self.addresses = addresses
        self.tunnelDoHURL = tunnelDoHURL
        self.blocksAds = blocksAds
    }

    public static let adguard = DNSProviderPreset(
        id: "adguard",
        name: "AdGuard DNS",
        detail: "Blocks ads and trackers upstream",
        transport: .https,
        serverURL: "https://dns.adguard-dns.com/dns-query",
        addresses: ["94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"],
        tunnelDoHURL: "https://94.140.14.14/dns-query",
        blocksAds: true
    )

    public static let adguardFamily = DNSProviderPreset(
        id: "adguard-family",
        name: "AdGuard DNS Family",
        detail: "Ads, trackers and adult content",
        transport: .https,
        serverURL: "https://family.adguard-dns.com/dns-query",
        addresses: ["94.140.14.15", "94.140.15.16", "2a10:50c0::bad1:ff", "2a10:50c0::bad2:ff"],
        blocksAds: true
    )

    public static let cloudflare = DNSProviderPreset(
        id: "cloudflare",
        name: "Cloudflare 1.1.1.1",
        detail: "Fast, neutral — no upstream blocking",
        transport: .https,
        serverURL: "https://cloudflare-dns.com/dns-query",
        addresses: ["1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"],
        tunnelDoHURL: "https://1.1.1.1/dns-query",
        blocksAds: false
    )

    public static let quad9 = DNSProviderPreset(
        id: "quad9",
        name: "Quad9",
        detail: "Blocks malware domains",
        transport: .https,
        serverURL: "https://dns.quad9.net/dns-query",
        addresses: ["9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"],
        tunnelDoHURL: "https://9.9.9.9:5053/dns-query",
        blocksAds: false
    )

    public static let mullvadAdblock = DNSProviderPreset(
        id: "mullvad-adblock",
        name: "Mullvad DNS (ad-blocking)",
        detail: "Blocks ads and trackers upstream",
        transport: .https,
        serverURL: "https://adblock.dns.mullvad.net/dns-query",
        addresses: ["194.242.2.3", "2a07:e340::3"],
        blocksAds: true
    )

    /// Presets shown in the profile-export picker.
    public static let all: [DNSProviderPreset] = [
        .adguard, .adguardFamily, .mullvadAdblock, .cloudflare, .quad9,
    ]

    /// NextDNS with a user-supplied configuration ID.
    public static func nextDNS(configID: String) -> DNSProviderPreset {
        let trimmed = configID.trimmingCharacters(in: .whitespaces)
        return DNSProviderPreset(
            id: "nextdns-\(trimmed)",
            name: "NextDNS",
            detail: "Configuration \(trimmed)",
            transport: .https,
            serverURL: "https://dns.nextdns.io/\(trimmed)",
            addresses: [],
            blocksAds: true
        )
    }
}
