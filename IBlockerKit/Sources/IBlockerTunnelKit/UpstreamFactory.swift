import Foundation
import IBlockerKit

/// Builds the concrete upstream for a stored configuration.
public enum UpstreamFactory {
    public static func make(_ config: UpstreamConfig) -> DNSUpstream? {
        switch config.kind {
        case .doh:
            return DoHUpstream(config: config)
        case .udp:
            #if canImport(Network)
            guard let address = config.udpAddress, !address.isEmpty else { return nil }
            return UDPUpstream(address: address)
            #else
            return nil
            #endif
        }
    }
}
