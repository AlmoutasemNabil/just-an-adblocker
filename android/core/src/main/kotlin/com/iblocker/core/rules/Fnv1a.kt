package com.iblocker.core.rules

/**
 * FNV-1a 64-bit hash. Domains are hashed once at compile time and once per
 * suffix at query time; 64 bits over <=1M entries makes accidental collisions
 * (worst case: one wrongly blocked domain, fixable via the allowlist)
 * vanishingly unlikely (~3e-8).
 */
object Fnv1a {
    fun hash64(string: String): ULong {
        var hash = 0xcbf29ce484222325uL
        for (byte in string.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toULong() and 0xFFuL)) * 0x100000001b3uL
        }
        return hash
    }
}
