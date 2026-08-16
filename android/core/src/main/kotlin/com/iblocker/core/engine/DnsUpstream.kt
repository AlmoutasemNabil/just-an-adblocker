package com.iblocker.core.engine

/**
 * A resolver the engine forwards non-blocked queries to.
 * Takes and returns raw DNS messages so EDNS options pass through untouched.
 */
interface DnsUpstream {
    suspend fun resolve(query: ByteArray): ByteArray

    /** Releases sockets/connections. Called when the upstream is swapped out or the service stops. */
    fun close() {}
}

sealed class UpstreamException(message: String) : Exception(message) {
    class Timeout : UpstreamException("timeout")
    class BadResponse : UpstreamException("bad response")
    class ConnectionFailed(reason: String) : UpstreamException("connection failed: $reason")
}
