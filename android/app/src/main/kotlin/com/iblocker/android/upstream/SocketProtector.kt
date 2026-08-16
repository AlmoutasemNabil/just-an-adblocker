package com.iblocker.android.upstream

import java.net.DatagramSocket
import java.net.Socket

/**
 * Keeps the filter's own upstream sockets off the tun interface.
 *
 * The tun only routes the fake resolver addresses, so upstream traffic
 * already leaves over the physical interface — but a protected socket is
 * guaranteed to, whatever the routing table says. Implemented by the VPN
 * service; a no-op stand-in keeps the resolvers usable from tests and from
 * the Blocking Test screen.
 */
interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean

    companion object {
        val NONE = object : SocketProtector {
            override fun protect(socket: Socket) = true
            override fun protect(socket: DatagramSocket) = true
        }
    }
}
