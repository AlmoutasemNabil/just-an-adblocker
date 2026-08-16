package com.iblocker.android.upstream

import com.iblocker.core.engine.DnsUpstream
import com.iblocker.core.shared.UpstreamConfig

/** Builds the concrete upstream for a stored configuration. */
object UpstreamFactory {
    fun make(config: UpstreamConfig, protector: SocketProtector = SocketProtector.NONE): DnsUpstream? =
        when (config.kind) {
            UpstreamConfig.Kind.DOH -> config.dohURL
                ?.takeIf { it.startsWith("https://") }
                ?.let { DohUpstream(it, protector) }

            UpstreamConfig.Kind.UDP -> config.udpAddress
                ?.takeIf { it.isNotBlank() }
                ?.let { UdpUpstream(it, protector = protector) }
        }
}
