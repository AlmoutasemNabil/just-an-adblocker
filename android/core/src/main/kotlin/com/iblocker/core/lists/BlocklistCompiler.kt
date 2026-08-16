package com.iblocker.core.lists

import com.iblocker.core.rules.CompiledBlocklist
import com.iblocker.core.rules.DomainValidator
import com.iblocker.core.rules.FilterListParser
import com.iblocker.core.rules.Fnv1a
import com.iblocker.core.rules.SeedRules
import com.iblocker.core.shared.AppPaths

data class CompileStats(
    var blockedEntryCount: Int = 0,
    var listAllowEntryCount: Int = 0,
    var userAllowEntryCount: Int = 0,
    var userDenyEntryCount: Int = 0,
    var skippedLines: Int = 0,
    var perSourceCounts: MutableMap<String, Int> = LinkedHashMap(),
    var generation: UInt = 0u,
)

/**
 * Turns cached list texts + user allow/deny into the three compiled blobs the
 * service mmaps. Runs in the app process, where memory is plentiful — never
 * on the packet path.
 */
object BlocklistCompiler {

    fun compile(state: FilterListState, paths: AppPaths): CompileStats {
        val stats = CompileStats()
        val blockHashes = HashSet<ULong>()
        val listAllowHashes = HashSet<ULong>()

        paths.ensureDirectories()

        for (source in state.sources.filter { it.enabled }) {
            val text = SeedRules.bundledText(source.id) ?: run {
                val file = paths.cachedListFile(source.id)
                if (!file.isFile) return@run null
                try {
                    file.readText()
                } catch (_: Exception) {
                    null
                }
            } ?: continue

            val parsed = FilterListParser.parse(text)
            stats.skippedLines += parsed.skippedLines
            stats.perSourceCounts[source.id] = parsed.blockDomains.size
            state.metadata[source.id] = (state.metadata[source.id] ?: FilterListMetadata())
                .copy(entryCount = parsed.blockDomains.size)

            for (domain in parsed.blockDomains) blockHashes.add(Fnv1a.hash64(domain))
            for (domain in parsed.allowDomains) listAllowHashes.add(Fnv1a.hash64(domain))
        }

        // List-level @@ allows are exact-hash subtractions; user allows stay
        // separate so the service can honor suffix-level allows at runtime.
        blockHashes.removeAll(listAllowHashes)

        val userAllowHashes = normalizeToHashes(state.userAllowlist)
        val userDenyHashes = normalizeToHashes(state.userDenylist)

        state.generation += 1u
        state.compiledSeedVersion = SeedRules.VERSION
        val generation = state.generation

        CompiledBlocklist.write(blockHashes, generation, paths.blocklistFile)
        CompiledBlocklist.write(userAllowHashes, generation, paths.userAllowlistFile)
        CompiledBlocklist.write(userDenyHashes, generation, paths.userDenylistFile)

        stats.blockedEntryCount = blockHashes.size
        stats.listAllowEntryCount = listAllowHashes.size
        stats.userAllowEntryCount = userAllowHashes.size
        stats.userDenyEntryCount = userDenyHashes.size
        stats.generation = generation
        return stats
    }

    private fun normalizeToHashes(domains: List<String>): Set<ULong> {
        val hashes = HashSet<ULong>()
        for (raw in domains) {
            DomainValidator.normalize(raw)?.let { hashes.add(Fnv1a.hash64(it)) }
        }
        return hashes
    }
}
