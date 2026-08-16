package com.iblocker.android.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Resolves a hostname through the SYSTEM resolver, which on device goes
 * through the tun interface — so the outcome measures exactly what any other
 * app's ad SDK would experience.
 */
object BlockingProbe {

    sealed interface Outcome {
        /**
         * Every returned address is a blackhole (0.0.0.0 / ::) — an ad SDK
         * gets nowhere. This is what the filter synthesizes for blocked names.
         */
        data object Blocked : Outcome

        /** Real addresses came back. */
        data class Resolved(val addresses: List<String>) : Outcome

        /**
         * Lookup failed outright (NXDOMAIN, no resolver, …). For an ad domain
         * this also means ads cannot load.
         */
        data class Unreachable(val reason: String) : Outcome
    }

    suspend fun probe(host: String): Outcome = withContext(Dispatchers.IO) { probeSync(host) }

    fun probeSync(host: String): Outcome = try {
        val addresses = InetAddress.getAllByName(host).map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
        when {
            addresses.isEmpty() -> Outcome.Unreachable("no addresses")
            addresses.all { it.isBlackhole() } -> Outcome.Blocked
            else -> Outcome.Resolved(addresses)
        }
    } catch (error: Exception) {
        Outcome.Unreachable(error.message ?: "lookup failed")
    }

    private fun String.isBlackhole(): Boolean =
        this == "0.0.0.0" || this == "::" || this == "0:0:0:0:0:0:0:0"
}
