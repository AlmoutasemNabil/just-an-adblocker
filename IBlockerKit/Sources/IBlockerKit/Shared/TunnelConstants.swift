import Foundation

/// The tunnel's synthetic network. Only the fake DNS addresses are routed
/// into the utun, so every non-DNS packet stays on the physical interface.
/// 198.18.0.0/15 is reserved for benchmarking (RFC 2544) and never appears
/// on the real internet; fd00::/8 is local IPv6.
public enum TunnelConstants {
    public static let tunnelIPv4 = "198.18.0.1"
    public static let tunnelIPv4SubnetMask = "255.255.255.0"
    public static let dnsIPv4 = "198.18.0.2"

    public static let tunnelIPv6 = "fd00::1"
    public static let tunnelIPv6PrefixLength = 64
    public static let dnsIPv6 = "fd00::2"

    public static let mtu = 1500

    /// Cosmetic "server" shown in Settings ▸ VPN.
    public static let serverDescription = "IBlocker (on-device)"
}
