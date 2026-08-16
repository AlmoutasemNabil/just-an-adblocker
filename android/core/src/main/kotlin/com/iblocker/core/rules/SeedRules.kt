package com.iblocker.core.rules

import com.iblocker.core.lists.FilterListState

/**
 * Rulesets compiled into the app binary, always available without any
 * download. Two bundled sources:
 *
 * - `bundled-core`: the guaranteed in-app-ad blocking floor (Google/AdMob
 *   serving + measurement domains, major third-party mobile ad SDKs).
 * - `bundled-dns-bypass`: the encrypted-DNS endpoints apps use to route
 *   around the system resolver. This is the Android counterpart of the iOS
 *   build's Apple-tracker-relay ruleset: a DNS filter only sees lookups that
 *   reach the system resolver, and an app (usually a browser) that speaks
 *   DoH straight to a hardcoded endpoint never asks. Symptom: ad domains
 *   show "Blocked" in the log while ads keep loading in that one app.
 *   Blocking the endpoints makes those apps fall back to system DNS, where
 *   the filter can see them. Trade-off: if you also set one of these
 *   hostnames as your system Private DNS, that setting stops working while
 *   protection is on — pick a different provider or turn this ruleset off.
 *
 * Both are merged at every compile AND injected directly into the service's
 * in-memory matcher ([fallbackHashes]), so the floor holds even against a
 * missing, stale, or corrupt compiled blob.
 *
 * Curation rule: only domains that are safe to block without hanging apps.
 * Unity Ads config hosts are deliberately absent (blocking them stalls some
 * games); the downloadable lists carve those out properly.
 */
object SeedRules {

    const val SOURCE_ID = "bundled-core"
    const val BYPASS_SOURCE_ID = "bundled-dns-bypass"

    /**
     * Bump whenever any bundled text changes: the app recompiles the
     * on-device blob at launch when its stamp differs, so app updates
     * propagate new built-in rules without waiting for a list refresh.
     */
    const val VERSION: UInt = 4u

    val text = """
        ! IBlocker built-in core rules (compiled into the app)
        ! Google in-app ads + ad measurement — the guaranteed floor
        ||doubleclick.net^
        ||googlesyndication.com^
        ||googleadservices.com^
        ||admob.com^
        ||adservice.google.com^
        ||googletagservices.com^
        ||app-measurement.com^
        ||google-analytics.com^
        ! Major third-party mobile ad SDKs (safe-to-block set)
        ||applovin.com^
        ||vungle.com^
        ||chartboost.com^
        ||inmobi.com^
        ||supersonicads.com^
        ||adcolony.com^
        ||mopub.com^
        ||smaato.net^
        ||pubmatic.com^
    """.trimIndent()

    val bypassText = """
        ! Encrypted-DNS endpoints that let an app skip the system resolver.
        ! Blocking these pushes those apps back onto system DNS, where this
        ! filter can see (and block) their ad lookups. See SeedRules docs.
        ||mozilla.cloudflare-dns.com^
        ||chrome.cloudflare-dns.com^
        ||doh.opendns.com^
        ||doh.familyshield.opendns.com^
        ||doh.cleanbrowsing.org^
        ||doh.xfinity.com^
        ||dns.nextdns.io^
        ||dns11.quad9.net^
        ||doh-de.blahdns.com^
        ||dns.dnsoverhttps.net^
    """.trimIndent()

    /**
     * Firefox's DoH canary. Answering it with NXDOMAIN (not a blackhole
     * address — the check is specifically for a name that does not exist)
     * tells the browser to leave DNS to the system, i.e. to this filter.
     * Answered by [com.iblocker.core.engine.DnsProxyEngine] whenever
     * the bypass ruleset is on.
     */
    val canaryDomains = setOf("use-application-dns.net")

    val bundledTexts: Map<String, String> = mapOf(
        SOURCE_ID to text,
        BYPASS_SOURCE_ID to bypassText,
    )

    fun bundledText(sourceID: String): String? = bundledTexts[sourceID]

    /** Hashes of the core rules (kept for tests and tooling). */
    val blockHashes: Set<ULong> by lazy { hashes(text) }

    fun hashes(ruleText: String): Set<ULong> =
        FilterListParser.parse(ruleText).blockDomains.map { Fnv1a.hash64(it) }.toSet()

    /**
     * The service's in-memory floor: the union of every bundled source the
     * user has left enabled. Sources absent from saved state count as
     * enabled (fresh installs, upgrades from older versions).
     */
    fun fallbackHashes(state: FilterListState): Set<ULong> {
        val result = HashSet<ULong>()
        for ((id, ruleText) in bundledTexts) {
            val enabled = state.sources.firstOrNull { it.id == id }?.enabled ?: true
            if (enabled) result.addAll(hashes(ruleText))
        }
        return result
    }

    /** True when the bundled DoH-endpoint ruleset is on, which also arms the canary answer. */
    fun isBypassBlockEnabled(state: FilterListState): Boolean =
        state.sources.firstOrNull { it.id == BYPASS_SOURCE_ID }?.enabled ?: true
}
