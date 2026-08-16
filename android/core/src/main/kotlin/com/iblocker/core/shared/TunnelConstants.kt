package com.iblocker.core.shared

/**
 * The tun device's synthetic network. Only the fake DNS addresses are routed
 * into the interface, so every non-DNS packet stays on the physical one.
 * 198.18.0.0/15 is reserved for benchmarking (RFC 2544) and never appears on
 * the real internet; fd00::/8 is local IPv6.
 */
object TunnelConstants {
    const val TUNNEL_IPV4 = "198.18.0.1"
    const val TUNNEL_IPV4_PREFIX_LENGTH = 24
    const val DNS_IPV4 = "198.18.0.2"

    const val TUNNEL_IPV6 = "fd00::1"
    const val TUNNEL_IPV6_PREFIX_LENGTH = 128
    const val DNS_IPV6 = "fd00::2"

    const val MTU = 1500

    /** Shown as the session name in Android's VPN status sheet. */
    const val SESSION_NAME = "IBlocker (on-device)"
}
