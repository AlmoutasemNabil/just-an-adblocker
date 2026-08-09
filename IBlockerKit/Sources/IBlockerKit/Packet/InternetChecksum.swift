import Foundation

/// RFC 1071 internet checksum, plus the IPv4/IPv6 UDP pseudo-header variants.
public enum InternetChecksum {

    /// Running 16-bit ones-complement sum of `bytes`, big-endian pairs.
    /// An odd trailing byte is padded with zero on the right.
    public static func sum16(_ bytes: [UInt8], initial: UInt32 = 0) -> UInt32 {
        var s = initial
        var i = 0
        let end = bytes.count
        while i + 1 < end {
            s &+= (UInt32(bytes[i]) << 8) | UInt32(bytes[i + 1])
            i += 2
        }
        if i < end {
            s &+= UInt32(bytes[i]) << 8
        }
        return s
    }

    /// Folds carries and returns the ones-complement of the sum.
    public static func finalize(_ sum: UInt32) -> UInt16 {
        var s = sum
        while s > 0xFFFF {
            s = (s & 0xFFFF) &+ (s >> 16)
        }
        return ~UInt16(truncatingIfNeeded: s)
    }

    /// Checksum for an IPv4 header whose checksum field bytes are zeroed.
    public static func ipv4Header(_ header: [UInt8]) -> UInt16 {
        finalize(sum16(header))
    }

    /// UDP checksum over the pseudo-header and the full UDP segment
    /// (header with zeroed checksum field + payload).
    ///
    /// Per RFC 768 a computed checksum of 0x0000 is transmitted as 0xFFFF.
    /// The checksum is optional for IPv4 but mandatory for IPv6, so we always
    /// compute it.
    public static func udp(ipVersion: Int, source: [UInt8], destination: [UInt8], segment: [UInt8]) -> UInt16 {
        var s: UInt32 = 0
        s = sum16(source, initial: s)
        s = sum16(destination, initial: s)
        let length = UInt32(segment.count)
        if ipVersion == 4 {
            // zero + protocol, then 16-bit UDP length
            s &+= 17
            s &+= length & 0xFFFF
        } else {
            // 32-bit upper-layer length, then 3 zero bytes + next header
            s &+= (length >> 16) & 0xFFFF
            s &+= length & 0xFFFF
            s &+= 17
        }
        s = sum16(segment, initial: s)
        let c = finalize(s)
        return c == 0 ? 0xFFFF : c
    }
}
