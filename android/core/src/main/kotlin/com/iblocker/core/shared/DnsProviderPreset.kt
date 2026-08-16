package com.iblocker.core.shared

/**
 * Encrypted-DNS providers offered for two purposes:
 *
 *  - `privateDnsHostname` — what you type into Android's **Private DNS**
 *    setting (DNS-over-TLS, system-wide, no VPN slot used). This is the
 *    Android counterpart of the iOS build's `.mobileconfig` export: same
 *    outcome — the *provider* does the blocking — with no file to install,
 *    because Android has the setting built in.
 *  - `tunnelDoHURL` — an IP-literal DoH endpoint usable as the filter's own
 *    upstream, where hostname resolution would recurse through the tun
 *    interface we are serving.
 */
data class DnsProviderPreset(
    val id: String,
    val name: String,
    val detail: String,
    /** DoT hostname for Android's Private DNS field, when the provider offers one. */
    val privateDnsHostname: String?,
    /** Resolver IPs, for the plain-UDP upstream option. */
    val addresses: List<String>,
    val tunnelDoHURL: String? = null,
    /** True when the provider filters ads itself (what matters in Private DNS mode). */
    val blocksAds: Boolean,
) {
    companion object {
        val adguard = DnsProviderPreset(
            id = "adguard",
            name = "AdGuard DNS",
            detail = "Blocks ads and trackers upstream",
            privateDnsHostname = "dns.adguard-dns.com",
            addresses = listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
            tunnelDoHURL = "https://94.140.14.14/dns-query",
            blocksAds = true,
        )

        val adguardFamily = DnsProviderPreset(
            id = "adguard-family",
            name = "AdGuard DNS Family",
            detail = "Ads, trackers and adult content",
            privateDnsHostname = "family.adguard-dns.com",
            addresses = listOf("94.140.14.15", "94.140.15.16", "2a10:50c0::bad1:ff", "2a10:50c0::bad2:ff"),
            blocksAds = true,
        )

        val mullvadAdblock = DnsProviderPreset(
            id = "mullvad-adblock",
            name = "Mullvad DNS (ad-blocking)",
            detail = "Blocks ads and trackers upstream",
            privateDnsHostname = "adblock.dns.mullvad.net",
            addresses = listOf("194.242.2.3", "2a07:e340::3"),
            blocksAds = true,
        )

        val cloudflare = DnsProviderPreset(
            id = "cloudflare",
            name = "Cloudflare 1.1.1.1",
            detail = "Fast, neutral — no upstream blocking",
            privateDnsHostname = "one.one.one.one",
            addresses = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
            tunnelDoHURL = "https://1.1.1.1/dns-query",
            blocksAds = false,
        )

        val quad9 = DnsProviderPreset(
            id = "quad9",
            name = "Quad9",
            detail = "Blocks malware domains",
            privateDnsHostname = "dns.quad9.net",
            addresses = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
            tunnelDoHURL = "https://9.9.9.9:5053/dns-query",
            blocksAds = false,
        )

        /** Presets shown in the Private DNS guide. */
        val all: List<DnsProviderPreset> = listOf(adguard, adguardFamily, mullvadAdblock, cloudflare, quad9)

        /** NextDNS with a user-supplied configuration ID. */
        fun nextDNS(configID: String): DnsProviderPreset {
            val trimmed = configID.trim()
            return DnsProviderPreset(
                id = "nextdns-$trimmed",
                name = "NextDNS",
                detail = "Configuration $trimmed",
                privateDnsHostname = "$trimmed.dns.nextdns.io",
                addresses = emptyList(),
                blocksAds = true,
            )
        }

        /**
         * Upstreams usable from inside the tunnel (IP-literal endpoints only —
         * hostname DoH would recurse through the filter's own resolver).
         */
        val tunnelUpstreams: List<Pair<String, UpstreamConfig>> = listOf(
            "Cloudflare (DoH)" to UpstreamConfig(UpstreamConfig.Kind.DOH, dohURL = "https://1.1.1.1/dns-query"),
            "AdGuard DNS (DoH, extra blocking)" to UpstreamConfig(UpstreamConfig.Kind.DOH, dohURL = "https://94.140.14.14/dns-query"),
            "Quad9 (DoH, malware blocking)" to UpstreamConfig(UpstreamConfig.Kind.DOH, dohURL = "https://9.9.9.9:5053/dns-query"),
            "Cloudflare (UDP 53)" to UpstreamConfig(UpstreamConfig.Kind.UDP, udpAddress = "1.1.1.1"),
            "Quad9 (UDP 53)" to UpstreamConfig(UpstreamConfig.Kind.UDP, udpAddress = "9.9.9.9"),
        )
    }
}
