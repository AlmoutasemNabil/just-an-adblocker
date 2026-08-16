package com.iblocker.core.rules

enum class Verdict {
    ALLOW,
    BLOCK,
    NONE,
}

/**
 * Decides the fate of a queried domain.
 *
 * Precedence: user allowlist > user denylist > built-in floor > compiled
 * blocklist. (List-level `@@` allows are already subtracted at compile time.)
 * A name matches when the exact name or any parent suffix down to two labels
 * is in a set — `x.ads.example.com` matches an `ads.example.com` entry; bare
 * TLDs never match.
 */
class DomainMatcher(
    val blocklist: CompiledBlocklistView? = null,
    val userAllowlist: CompiledBlocklistView? = null,
    val userDenylist: CompiledBlocklistView? = null,
    /**
     * In-memory always-on rules (the seed fallback). Independent of the
     * compiled blobs so the service keeps its guaranteed floor even when the
     * on-disk blocklist is missing or predates an app update.
     */
    val builtInBlockHashes: Set<ULong> = emptySet(),
) {
    val blockedEntryCount: Int get() = (blocklist?.count ?: 0) + builtInBlockHashes.size

    fun verdict(domain: String): Verdict {
        val hashes = suffixHashes(domain)
        if (hashes.isEmpty()) return Verdict.NONE

        userAllowlist?.takeIf { !it.isEmpty }?.let { allow ->
            for (hash in hashes) if (allow.contains(hash)) return Verdict.ALLOW
        }
        userDenylist?.takeIf { !it.isEmpty }?.let { deny ->
            for (hash in hashes) if (deny.contains(hash)) return Verdict.BLOCK
        }
        if (builtInBlockHashes.isNotEmpty()) {
            for (hash in hashes) if (builtInBlockHashes.contains(hash)) return Verdict.BLOCK
        }
        blocklist?.takeIf { !it.isEmpty }?.let { block ->
            for (hash in hashes) if (block.contains(hash)) return Verdict.BLOCK
        }
        return Verdict.NONE
    }

    companion object {
        val EMPTY = DomainMatcher()

        /**
         * FNV-1a hashes of the name and each parent suffix with >=2 labels,
         * most-specific first: a.b.example.com -> [a.b.example.com, b.example.com, example.com]
         */
        fun suffixHashes(domain: String): List<ULong> {
            val labels = domain.split(".")
            if (labels.size < 2) return emptyList()
            val hashes = ArrayList<ULong>(labels.size - 1)
            for (start in 0..(labels.size - 2)) {
                hashes.add(Fnv1a.hash64(labels.subList(start, labels.size).joinToString(".")))
            }
            return hashes
        }
    }
}
