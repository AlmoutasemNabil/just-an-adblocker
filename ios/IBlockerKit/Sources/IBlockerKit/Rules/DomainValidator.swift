import Foundation

public enum DomainValidator {

    /// Normalizes a hostname for hashing and matching: lowercase, trailing
    /// dot stripped. Returns nil for anything that is not a plausible
    /// blockable domain: single labels, empty labels, non-ASCII (v1 skips
    /// IDN — the major lists ship punycoded), illegal characters, and
    /// over-long names/labels.
    public static func normalize(_ raw: String) -> String? {
        var s = raw.trimmingCharacters(in: .whitespaces).lowercased()
        if s.hasSuffix(".") { s.removeLast() }
        guard s.count >= 3, s.count <= 253, s.contains(".") else { return nil }

        var labelLength = 0
        var previousWasDot = true    // catches a leading dot
        for scalar in s.unicodeScalars {
            switch scalar {
            case ".":
                if previousWasDot { return nil }
                labelLength = 0
                previousWasDot = true
            case "a"..."z", "0"..."9", "-", "_":
                labelLength += 1
                if labelLength > 63 { return nil }
                previousWasDot = false
            default:
                return nil
            }
        }
        if previousWasDot { return nil }
        return s
    }
}
