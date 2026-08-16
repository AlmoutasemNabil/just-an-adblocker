package com.iblocker.android.vpn

enum class TunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    /** The service could not establish a tun interface (permission revoked, another VPN active). */
    FAILED,
    ;

    val isOn: Boolean get() = this == CONNECTED || this == CONNECTING
}
