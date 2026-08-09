import Foundation

public enum DNSWireError: Error, Equatable {
    case truncated
    case badLabel
    case pointerLoop
    case nameTooLong
    case notAQuery
    case unsupportedQuestionCount
}

/// Encoder/decoder for DNS wire-format names (RFC 1035 section 3.1).
enum DNSNameCodec {

    /// Decodes a name starting at `offset`.
    ///
    /// Returns the lowercased dotted name (no trailing dot) and the number of
    /// bytes the name occupies at `offset` (up to and including the zero label,
    /// or the 2-byte compression pointer if one is encountered first).
    /// Compression pointers are followed, but only backwards, so malicious
    /// loops terminate.
    static func decode(_ bytes: [UInt8], at offset: Int) throws -> (name: String, wireLength: Int) {
        var labels: [String] = []
        var i = offset
        var wireLength = -1
        var nameBytes = 0

        while true {
            guard i < bytes.count else { throw DNSWireError.truncated }
            let len = Int(bytes[i])

            if len == 0 {
                if wireLength < 0 { wireLength = i - offset + 1 }
                break
            }

            if len & 0xC0 == 0xC0 {
                guard i + 1 < bytes.count else { throw DNSWireError.truncated }
                if wireLength < 0 { wireLength = i - offset + 2 }
                let target = ((len & 0x3F) << 8) | Int(bytes[i + 1])
                guard target < i else { throw DNSWireError.pointerLoop }
                i = target
                continue
            }

            guard len & 0xC0 == 0 else { throw DNSWireError.badLabel }
            guard i + 1 + len <= bytes.count else { throw DNSWireError.truncated }
            nameBytes += len + 1
            guard nameBytes <= 255 else { throw DNSWireError.nameTooLong }

            var label = ""
            label.reserveCapacity(len)
            for j in (i + 1)...(i + len) {
                var c = bytes[j]
                if c >= 0x41 && c <= 0x5A { c += 0x20 }
                label.unicodeScalars.append(UnicodeScalar(c))
            }
            labels.append(label)
            i += len + 1
        }

        return (labels.joined(separator: "."), wireLength)
    }

    /// Encodes a dotted name into wire format (no compression).
    /// Returns nil if the name is not encodable.
    static func encode(_ name: String) -> [UInt8]? {
        var out: [UInt8] = []
        for label in name.split(separator: ".", omittingEmptySubsequences: false) {
            let utf8 = Array(label.utf8)
            guard !utf8.isEmpty, utf8.count <= 63 else { return nil }
            out.append(UInt8(utf8.count))
            out.append(contentsOf: utf8)
        }
        out.append(0)
        guard out.count <= 255 else { return nil }
        return out
    }
}
