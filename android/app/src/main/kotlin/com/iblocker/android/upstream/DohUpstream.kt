package com.iblocker.android.upstream

import com.iblocker.core.engine.DnsUpstream
import com.iblocker.core.engine.UpstreamException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * DNS-over-HTTPS upstream (RFC 8484 POST).
 *
 * The default upstream. HTTP/2 multiplexing gives per-query concurrency for
 * free and, because the exchange is TCP-based on our side, upstream
 * truncation never happens.
 *
 * IMPORTANT: the endpoint URL must use an IP literal
 * (https://1.1.1.1/dns-query). A hostname would be resolved through the
 * system resolver — which points at the tun interface we are serving.
 */
class DohUpstream(
    private val url: String,
    protector: SocketProtector = SocketProtector.NONE,
    timeoutSeconds: Long = 4,
) : DnsUpstream {

    private val mediaType = "application/dns-message".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(ProtectedSocketFactory(protector))
        .callTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun resolve(query: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-message")
            .post(query.toRequestBody(mediaType))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful || body.size < 12) throw UpstreamException.BadResponse()
                body
            }
        } catch (error: IOException) {
            throw UpstreamException.ConnectionFailed(error.message ?: "I/O error")
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private class ProtectedSocketFactory(private val protector: SocketProtector) : SocketFactory() {
        private fun newSocket(): Socket = Socket().also { protector.protect(it) }

        override fun createSocket(): Socket = newSocket()

        override fun createSocket(host: String, port: Int): Socket =
            newSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            newSocket().apply {
                bind(java.net.InetSocketAddress(localHost, localPort))
                connect(java.net.InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            newSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = newSocket().apply {
            bind(java.net.InetSocketAddress(localAddress, localPort))
            connect(java.net.InetSocketAddress(address, port))
        }
    }
}
