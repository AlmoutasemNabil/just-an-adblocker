package com.iblocker.android.upstream

import com.iblocker.core.engine.DnsUpstream
import com.iblocker.core.engine.UpstreamException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Plain DNS-over-UDP:53 upstream.
 *
 * One long-lived connected socket serves every client, so transaction IDs are
 * rewritten to a private sequence and demultiplexed on receive. The address
 * must be an IP literal — hostname resolution inside the service would
 * recurse through the interface it is serving.
 */
class UdpUpstream(
    private val address: String,
    private val port: Int = 53,
    private val protector: SocketProtector = SocketProtector.NONE,
    private val timeoutMillis: Long = 3_000,
) : DnsUpstream {

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var closed = false

    private var nextID = 0
    private val idLock = Any()

    override suspend fun resolve(query: ByteArray): ByteArray {
        var lastError: Exception = UpstreamException.Timeout()
        repeat(2) {
            try {
                return attempt(query)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError
    }

    private suspend fun attempt(query: ByteArray): ByteArray {
        if (query.size < 12) throw UpstreamException.BadResponse()
        val socket = ensureSocket()

        val bytes = query.copyOf()
        val originalID = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)

        val ourID = synchronized(idLock) {
            var candidate = (nextID + 1) and 0xFFFF
            var attempts = 0
            while (pending.containsKey(candidate) && attempts < 0x10000) {
                candidate = (candidate + 1) and 0xFFFF
                attempts += 1
            }
            nextID = candidate
            candidate
        }
        bytes[0] = ((ourID shr 8) and 0xFF).toByte()
        bytes[1] = (ourID and 0xFF).toByte()

        val waiter = CompletableDeferred<ByteArray>()
        pending[ourID] = waiter
        try {
            withContext(Dispatchers.IO) {
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(address), port))
            }
            val response = withTimeout(timeoutMillis) { waiter.await() }
            if (response.size < 12) throw UpstreamException.BadResponse()
            val out = response.copyOf()
            out[0] = ((originalID shr 8) and 0xFF).toByte()
            out[1] = (originalID and 0xFF).toByte()
            return out
        } catch (_: TimeoutCancellationException) {
            throw UpstreamException.Timeout()
        } catch (error: Exception) {
            if (error is UpstreamException) throw error
            throw UpstreamException.ConnectionFailed(error.message ?: "send failed")
        } finally {
            pending.remove(ourID)
        }
    }

    @Synchronized
    private fun ensureSocket(): DatagramSocket {
        socket?.takeIf { !it.isClosed }?.let { return it }
        val created = DatagramSocket()
        protector.protect(created)
        socket = created
        startReceiveLoop(created)
        return created
    }

    private fun startReceiveLoop(socket: DatagramSocket) {
        thread(isDaemon = true, name = "iblocker-udp-upstream") {
            val buffer = ByteArray(4096)
            while (!closed && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.length < 2) continue
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    pending.remove(id)?.complete(data)
                } catch (_: Exception) {
                    if (closed) break
                    // Socket died: drop it so the next query reconnects, and
                    // fail everyone waiting rather than leaving them to time out.
                    failAllWaiting("socket closed")
                    break
                }
            }
        }
    }

    private fun failAllWaiting(reason: String) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(UpstreamException.ConnectionFailed(reason)) }
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    override fun close() {
        closed = true
        failAllWaiting("upstream closed")
    }
}
