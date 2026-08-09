import Foundation

public enum Verdict: Sendable, Equatable {
    case allow
    case block
    case none
}

/// Decides the fate of a queried domain.
///
/// Precedence: user allowlist > user denylist > compiled blocklist.
/// (List-level `@@` allows are already subtracted at compile time.)
/// A name matches when the exact name or any parent suffix down to two
/// labels is in a set — `x.ads.example.com` matches an `ads.example.com`
/// entry; bare TLDs never match.
public struct DomainMatcher: Sendable {
    public let blocklist: CompiledBlocklistView?
    public let userAllowlist: CompiledBlocklistView?
    public let userDenylist: CompiledBlocklistView?
    /// In-memory always-on rules (the seed fallback). Independent of the
    /// compiled blobs so the tunnel keeps its guaranteed floor even when the
    /// on-disk blocklist is missing or predates an app update.
    public let builtInBlockHashes: Set<UInt64>

    public init(blocklist: CompiledBlocklistView?,
                userAllowlist: CompiledBlocklistView? = nil,
                userDenylist: CompiledBlocklistView? = nil,
                builtInBlockHashes: Set<UInt64> = []) {
        self.blocklist = blocklist
        self.userAllowlist = userAllowlist
        self.userDenylist = userDenylist
        self.builtInBlockHashes = builtInBlockHashes
    }

    public static let empty = DomainMatcher(blocklist: nil)

    public var blockedEntryCount: Int { (blocklist?.count ?? 0) + builtInBlockHashes.count }

    public func verdict(for domain: String) -> Verdict {
        let hashes = Self.suffixHashes(of: domain)
        guard !hashes.isEmpty else { return .none }

        if let allow = userAllowlist, !allow.isEmpty {
            for hash in hashes where allow.contains(hash) { return .allow }
        }
        if let deny = userDenylist, !deny.isEmpty {
            for hash in hashes where deny.contains(hash) { return .block }
        }
        if !builtInBlockHashes.isEmpty {
            for hash in hashes where builtInBlockHashes.contains(hash) { return .block }
        }
        if let block = blocklist, !block.isEmpty {
            for hash in hashes where block.contains(hash) { return .block }
        }
        return .none
    }

    /// FNV-1a hashes of the name and each parent suffix with ≥2 labels,
    /// most-specific first: a.b.example.com → [a.b.example.com, b.example.com, example.com]
    public static func suffixHashes(of domain: String) -> [UInt64] {
        let labels = domain.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 2 else { return [] }
        var hashes: [UInt64] = []
        hashes.reserveCapacity(labels.count - 1)
        for start in 0...(labels.count - 2) {
            hashes.append(FNV1a.hash64(labels[start...].joined(separator: ".")))
        }
        return hashes
    }
}
