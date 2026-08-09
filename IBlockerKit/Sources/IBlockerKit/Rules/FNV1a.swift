import Foundation

/// FNV-1a 64-bit hash. Domains are hashed once at compile time and once per
/// suffix at query time; 64 bits over ≤1M entries makes accidental collisions
/// (worst case: one wrongly blocked domain, fixable via the allowlist)
/// vanishingly unlikely (~3e-8).
public enum FNV1a {
    public static func hash64(_ string: String) -> UInt64 {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in string.utf8 {
            hash = (hash ^ UInt64(byte)) &* 0x100000001b3
        }
        return hash
    }
}
