import Foundation

/// A UDP datagram extracted from a raw IP packet as delivered by the tunnel.
public struct ParsedUDPPacket: Sendable, Equatable {
    public let ipVersion: Int
    /// 4 bytes for IPv4, 16 for IPv6.
    public let sourceAddress: [UInt8]
    public let destinationAddress: [UInt8]
    public let sourcePort: UInt16
    public let destinationPort: UInt16
    public let payload: [UInt8]

    public init(ipVersion: Int, sourceAddress: [UInt8], destinationAddress: [UInt8],
                sourcePort: UInt16, destinationPort: UInt16, payload: [UInt8]) {
        self.ipVersion = ipVersion
        self.sourceAddress = sourceAddress
        self.destinationAddress = destinationAddress
        self.sourcePort = sourcePort
        self.destinationPort = destinationPort
        self.payload = payload
    }
}

/// Parses raw IP packets from the tunnel into UDP datagrams.
///
/// Anything that is not a complete, unfragmented UDP packet returns nil and
/// is dropped by the caller: DNS queries are far below fragmentation size,
/// and TCP:53 is intentionally unsupported (we never set TC in responses, so
/// clients have no reason to retry over TCP).
public enum PacketParser {

    public static func parseUDP(_ packet: Data) -> ParsedUDPPacket? {
        let b = [UInt8](packet)
        guard !b.isEmpty else { return nil }
        switch b[0] >> 4 {
        case 4: return parseIPv4(b)
        case 6: return parseIPv6(b)
        default: return nil
        }
    }

    private static func parseIPv4(_ b: [UInt8]) -> ParsedUDPPacket? {
        guard b.count >= 20 else { return nil }
        let headerLength = Int(b[0] & 0x0F) * 4
        guard headerLength >= 20, b.count >= headerLength + 8 else { return nil }

        // Reject fragments: MF flag or nonzero fragment offset.
        let fragmentField = UInt16(b[6]) << 8 | UInt16(b[7])
        guard fragmentField & 0x3FFF == 0 else { return nil }

        guard b[9] == 17 else { return nil }

        let totalLength = Int(b[2]) << 8 | Int(b[3])
        guard totalLength >= headerLength + 8, b.count >= totalLength else { return nil }

        let sourcePort = UInt16(b[headerLength]) << 8 | UInt16(b[headerLength + 1])
        let destinationPort = UInt16(b[headerLength + 2]) << 8 | UInt16(b[headerLength + 3])
        let udpLength = Int(b[headerLength + 4]) << 8 | Int(b[headerLength + 5])
        guard udpLength >= 8, headerLength + udpLength <= totalLength else { return nil }

        return ParsedUDPPacket(
            ipVersion: 4,
            sourceAddress: Array(b[12..<16]),
            destinationAddress: Array(b[16..<20]),
            sourcePort: sourcePort,
            destinationPort: destinationPort,
            payload: Array(b[(headerLength + 8)..<(headerLength + udpLength)])
        )
    }

    private static func parseIPv6(_ b: [UInt8]) -> ParsedUDPPacket? {
        guard b.count >= 48 else { return nil }

        // Only a bare UDP next-header is accepted; extension-header chains
        // (including fragments, next header 44) are dropped.
        guard b[6] == 17 else { return nil }

        let payloadLength = Int(b[4]) << 8 | Int(b[5])
        guard payloadLength >= 8, b.count >= 40 + payloadLength else { return nil }

        let sourcePort = UInt16(b[40]) << 8 | UInt16(b[41])
        let destinationPort = UInt16(b[42]) << 8 | UInt16(b[43])
        let udpLength = Int(b[44]) << 8 | Int(b[45])
        guard udpLength >= 8, udpLength <= payloadLength else { return nil }

        return ParsedUDPPacket(
            ipVersion: 6,
            sourceAddress: Array(b[8..<24]),
            destinationAddress: Array(b[24..<40]),
            sourcePort: sourcePort,
            destinationPort: destinationPort,
            payload: Array(b[48..<(40 + udpLength)])
        )
    }
}
