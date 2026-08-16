package com.iblocker.core.engine

import com.iblocker.core.dns.DnsParser
import com.iblocker.core.dns.DnsQuery
import com.iblocker.core.dns.DnsResponseBuilder
import com.iblocker.core.log.BlockerStats
import com.iblocker.core.log.LogVerdict
import com.iblocker.core.log.QueryLogRecord
import com.iblocker.core.log.QueryLogRingWriter
import com.iblocker.core.log.StatsPersistence
import com.iblocker.core.packet.PacketParser
import com.iblocker.core.packet.ParsedUdpPacket
import com.iblocker.core.packet.UdpReplyBuilder
import com.iblocker.core.rules.DomainMatcher
import com.iblocker.core.rules.SeedRules
import com.iblocker.core.rules.Verdict
import com.iblocker.core.shared.TunnelConstants
import com.iblocker.core.shared.TunnelRuntimeStats
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The service's brain: raw IP packet in -> verdict -> raw IP packet out (or
 * null to drop). Pure logic over injected collaborators, so the whole path is
 * unit-testable off-device.
 *
 * Concurrency: state lives behind a mutex that is only ever held between
 * suspension points — never across an upstream lookup — so one slow resolver
 * round trip cannot stall the other queries in flight.
 */
class DnsProxyEngine(
    matcher: DomainMatcher,
    upstream: DnsUpstream,
    private val logWriter: QueryLogRingWriter?,
    private val statsFile: File?,
    private val configuration: Configuration = Configuration(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Configuration(
        val blockTTL: Int = 300,
        val maxInFlight: Int = 256,
        val mtu: Int = TunnelConstants.MTU,
        val logEnabled: Boolean = true,
        /**
         * Names answered with NXDOMAIN instead of a blackhole address while
         * blocking is on. Browsers probe these to decide whether to run their
         * own DNS; "does not exist" is the answer that hands DNS back to us.
         */
        val nxdomainNames: Set<String> = SeedRules.canaryDomains,
    )

    private val stateLock = Mutex()

    private var matcher: DomainMatcher = matcher
    private var upstream: DnsUpstream = upstream
    private var stats: BlockerStats = statsFile?.let { StatsPersistence.load(it) } ?: BlockerStats()
    private val startedAt = clock()
    private var inFlight = 0

    /** While set to a future instant, blocking is suspended and every query is forwarded. */
    private var pausedUntil: Long? = null

    // MARK: - Packet path

    suspend fun handlePacket(packet: ByteArray): ByteArray? {
        val udp = PacketParser.parseUDP(packet) ?: return null
        if (udp.destinationPort != 53) return null

        val query: DnsQuery = try {
            DnsParser.parseQuery(udp.payload)
        } catch (_: Exception) {
            // Responses, multi-question or exotic messages: forward untouched.
            return forward(udp.payload, null, udp)
        }

        if (!isPaused() && query.qclass == 1 && verdictFor(query.questionName) == Verdict.BLOCK) {
            record(query.questionName, query.qtype, LogVerdict.BLOCKED)
            val response = if (configuration.nxdomainNames.contains(query.questionName)) {
                DnsResponseBuilder.nxdomain(query)
            } else {
                DnsResponseBuilder.blocked(query, configuration.blockTTL)
            }
            return UdpReplyBuilder.reply(udp, response)
        }

        return forward(query.raw, query, udp)
    }

    private suspend fun forward(raw: ByteArray, query: DnsQuery?, udp: ParsedUdpPacket): ByteArray? {
        val resolver = stateLock.withLock {
            if (inFlight >= configuration.maxInFlight) return@withLock null
            inFlight += 1
            upstream
        }
        if (resolver == null) {
            if (query != null) {
                record(query.questionName, query.qtype, LogVerdict.FAILED)
                return UdpReplyBuilder.reply(udp, DnsResponseBuilder.servfail(query))
            }
            return null
        }

        try {
            val answer = resolver.resolve(raw)
            if (query != null) {
                record(query.questionName, query.qtype, LogVerdict.ALLOWED)
            }
            val ipOverhead = if (udp.ipVersion == 4) 28 else 48
            if (answer.size + ipOverhead > configuration.mtu) {
                if (query != null) {
                    return UdpReplyBuilder.reply(udp, DnsResponseBuilder.truncated(query))
                }
                return null
            }
            return UdpReplyBuilder.reply(udp, answer)
        } catch (_: Exception) {
            if (query != null) {
                record(query.questionName, query.qtype, LogVerdict.FAILED)
                return UdpReplyBuilder.reply(udp, DnsResponseBuilder.servfail(query))
            }
            return null
        } finally {
            stateLock.withLock { inFlight -= 1 }
        }
    }

    private suspend fun verdictFor(name: String): Verdict = stateLock.withLock { matcher.verdict(name) }

    private suspend fun record(name: String, qtype: Int, verdict: LogVerdict) {
        val now = clock()
        stateLock.withLock {
            stats.record(blocked = verdict == LogVerdict.BLOCKED, day = BlockerStats.dayKey(now))
            if (configuration.logEnabled) {
                logWriter?.append(
                    QueryLogRecord(
                        timestampMillis = now,
                        qtype = qtype,
                        verdict = verdict,
                        domain = name,
                    )
                )
            }
        }
    }

    // MARK: - Control

    suspend fun reload(matcher: DomainMatcher) = stateLock.withLock {
        this.matcher = matcher
    }

    suspend fun setUpstream(upstream: DnsUpstream) {
        val previous = stateLock.withLock {
            val old = this.upstream
            this.upstream = upstream
            old
        }
        if (previous !== upstream) runCatching { previous.close() }
    }

    /** Suspends blocking until [untilMillis] (null resumes immediately). */
    suspend fun setPaused(untilMillis: Long?) = stateLock.withLock {
        pausedUntil = untilMillis
    }

    private suspend fun isPaused(): Boolean = stateLock.withLock {
        (pausedUntil ?: return@withLock false) > clock()
    }

    suspend fun blocklistGeneration(): UInt? = stateLock.withLock { matcher.blocklist?.generation }

    suspend fun statsSnapshot(): TunnelRuntimeStats = stateLock.withLock {
        TunnelRuntimeStats(
            startedAtMillis = startedAt,
            totalQueries = stats.totalQueries,
            blockedQueries = stats.totalBlocked,
            blocklistEntryCount = matcher.blockedEntryCount.toLong(),
            pausedUntilMillis = pausedUntil?.takeIf { it > clock() },
        )
    }

    /**
     * Persists pending log records and counters. Called on a slow timer by
     * the service, before stats snapshots, and at shutdown.
     */
    suspend fun flush() = stateLock.withLock {
        runCatching { logWriter?.flush() }
        statsFile?.let { StatsPersistence.save(stats, it) }
        Unit
    }

    /** Final teardown: flush, then release the log file handle and the upstream. */
    suspend fun close() {
        flush()
        runCatching { logWriter?.close() }
        runCatching { stateLock.withLock { upstream }.close() }
    }
}
