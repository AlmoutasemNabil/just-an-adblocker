import Foundation

public struct CompileStats: Sendable, Equatable {
    public var blockedEntryCount: Int = 0
    public var listAllowEntryCount: Int = 0
    public var userAllowEntryCount: Int = 0
    public var userDenyEntryCount: Int = 0
    public var skippedLines: Int = 0
    public var perSourceCounts: [String: Int] = [:]
    public var generation: UInt32 = 0

    public init() {}
}

/// Turns cached list texts + user allow/deny into the three compiled blobs
/// the tunnel mmaps. Runs in the app process, where memory is plentiful.
public enum BlocklistCompiler {

    @discardableResult
    public static func compile(state: inout FilterListState, paths: AppGroupPaths) throws -> CompileStats {
        var stats = CompileStats()
        var blockHashes = Set<UInt64>()
        var listAllowHashes = Set<UInt64>()

        // A hidden source must not keep filtering from behind the curtain: a
        // relay list enabled before the UI was hidden is skipped, not compiled.
        for source in state.sources where source.enabled && !FeatureFlags.isHidden(sourceID: source.id) {
            let text: String
            if let bundled = SeedRules.bundledText(for: source.id) {
                text = bundled
            } else {
                let url = paths.cachedListURL(sourceID: source.id)
                guard let data = try? Data(contentsOf: url),
                      let cached = String(data: data, encoding: .utf8) else { continue }
                text = cached
            }

            let parsed = FilterListParser.parse(text)
            stats.skippedLines += parsed.skippedLines
            stats.perSourceCounts[source.id] = parsed.blockDomains.count
            var metadata = state.metadata[source.id] ?? FilterListMetadata()
            metadata.entryCount = parsed.blockDomains.count
            state.metadata[source.id] = metadata

            for domain in parsed.blockDomains {
                blockHashes.insert(FNV1a.hash64(domain))
            }
            for domain in parsed.allowDomains {
                listAllowHashes.insert(FNV1a.hash64(domain))
            }
        }

        // List-level @@ allows are exact-hash subtractions; user allows stay
        // separate so the tunnel can honor suffix-level allows at runtime.
        blockHashes.subtract(listAllowHashes)

        let userAllowHashes = normalizeToHashes(state.userAllowlist)
        let userDenyHashes = normalizeToHashes(state.userDenylist)

        state.generation &+= 1
        state.compiledSeedVersion = SeedRules.version
        let generation = state.generation

        try CompiledBlocklist.write(hashes: blockHashes, generation: generation, to: paths.blocklistURL)
        try CompiledBlocklist.write(hashes: userAllowHashes, generation: generation, to: paths.userAllowlistURL)
        try CompiledBlocklist.write(hashes: userDenyHashes, generation: generation, to: paths.userDenylistURL)

        stats.blockedEntryCount = blockHashes.count
        stats.listAllowEntryCount = listAllowHashes.count
        stats.userAllowEntryCount = userAllowHashes.count
        stats.userDenyEntryCount = userDenyHashes.count
        stats.generation = generation
        return stats
    }

    private static func normalizeToHashes(_ domains: [String]) -> Set<UInt64> {
        var hashes = Set<UInt64>()
        for raw in domains {
            if let domain = DomainValidator.normalize(raw) {
                hashes.insert(FNV1a.hash64(domain))
            }
        }
        return hashes
    }
}
