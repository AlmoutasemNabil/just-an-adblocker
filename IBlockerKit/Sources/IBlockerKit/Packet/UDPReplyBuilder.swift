import Foundation

/// Builds the raw IP reply packet for a request that arrived through the
/// tunnel: the 5-tuple is swapped and checksums are computed from scratch.
public enum UDPReplyBuilder {

    public static func reply(to request: ParsedUDPPacket, payload: [UInt8]) -> Data? {
        switch request.ipVersion {
        case 4: return ipv4Reply(to: request, payload: payload)
        case 6: return ipv6Reply(to: request, payload: payload)
        default: return nil
        }
    }

    private static func ipv4Reply(to request: ParsedUDPPacket, payload: [UInt8]) -> Data? {
        let udpLength = 8 + payload.count
        let totalLength = 20 + udpLength
        guard totalLength <= 0xFFFF,
              request.sourceAddress.count == 4,
              request.destinationAddress.count == 4 else { return nil }

        var header: [UInt8] = [
            0x45, 0x00,
            UInt8(totalLength >> 8), UInt8(totalLength & 0xFF),
            0x00, 0x00,             // identification (DF set, so 0 is valid per RFC 6864)
            0x40, 0x00,             // flags: DF
            64, 17,                 // TTL, protocol UDP
            0x00, 0x00,             // header checksum (placeholder)
        ]
        header.append(contentsOf: request.destinationAddress)  // our reply source
        header.append(contentsOf: request.sourceAddress)       // back to the client

        let headerChecksum = InternetChecksum.ipv4Header(header)
        header[10] = UInt8(headerChecksum >> 8)
        header[11] = UInt8(headerChecksum & 0xFF)

        let segment = udpSegment(
            sourcePort: request.destinationPort,
            destinationPort: request.sourcePort,
            payload: payload,
            ipVersion: 4,
            sourceAddress: request.destinationAddress,
            destinationAddress: request.sourceAddress
        )

        return Data(header + segment)
    }

    private static func ipv6Reply(to request: ParsedUDPPacket, payload: [UInt8]) -> Data? {
        let udpLength = 8 + payload.count
        guard udpLength <= 0xFFFF,
              request.sourceAddress.count == 16,
              request.destinationAddress.count == 16 else { return nil }

        var header: [UInt8] = [
            0x60, 0x00, 0x00, 0x00,
            UInt8(udpLength >> 8), UInt8(udpLength & 0xFF),
            17, 64,                 // next header UDP, hop limit
        ]
        header.append(contentsOf: request.destinationAddress)
        header.append(contentsOf: request.sourceAddress)

        let segment = udpSegment(
            sourcePort: request.destinationPort,
            destinationPort: request.sourcePort,
            payload: payload,
            ipVersion: 6,
            sourceAddress: request.destinationAddress,
            destinationAddress: request.sourceAddress
        )

        return Data(header + segment)
    }

    private static func udpSegment(sourcePort: UInt16, destinationPort: UInt16, payload: [UInt8],
                                   ipVersion: Int, sourceAddress: [UInt8], destinationAddress: [UInt8]) -> [UInt8] {
        let udpLength = 8 + payload.count
        var segment: [UInt8] = [
            UInt8(sourcePort >> 8), UInt8(sourcePort & 0xFF),
            UInt8(destinationPort >> 8), UInt8(destinationPort & 0xFF),
            UInt8(udpLength >> 8), UInt8(udpLength & 0xFF),
            0x00, 0x00,             // checksum (placeholder)
        ]
        segment.append(contentsOf: payload)

        let checksum = InternetChecksum.udp(
            ipVersion: ipVersion,
            source: sourceAddress,
            destination: destinationAddress,
            segment: segment
        )
        segment[6] = UInt8(checksum >> 8)
        segment[7] = UInt8(checksum & 0xFF)
        return segment
    }
}
