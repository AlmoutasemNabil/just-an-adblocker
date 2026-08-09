import Foundation

/// Rulesets compiled into the app binary, always available without any
/// download. Two bundled sources:
///
/// - `bundled-core`: the guaranteed in-app-ad blocking floor (Google/AdMob
///   serving + measurement domains, major third-party mobile ad SDKs).
/// - `bundled-apple-relay`: Apple's tracker-relay endpoints. iOS "Limit IP
///   Address Tracking" (default ON per network) routes known-tracker
///   connections through Apple's relay, where the tracker's hostname is
///   resolved REMOTELY — those connections never consult on-device DNS and
///   slip past any DNS filter. Symptom: ad domains show "Blocked" in the
///   log while in-app ads still load, with apple-relay.* in the Allowed
///   rows. Blocking the relay endpoints makes iOS fall back to direct
///   connections, which do go through the filter. Trade-off: paid iCloud+
///   Private Relay reports "unavailable" while enabled.
///
/// Both are merged at every compile AND injected directly into the tunnel's
/// in-memory matcher (`fallbackHashes`), so the floor holds even against a
/// missing, stale, or corrupt compiled blob.
///
/// Curation rule: only domains that are safe to block without hanging apps.
/// Unity Ads config hosts are deliberately absent (blocking them stalls
/// some games); the downloadable lists carve those out properly.
public enum SeedRules {

    public static let sourceID = "bundled-core"
    public static let relaySourceID = "bundled-apple-relay"

    /// Bump whenever any bundled text changes: the app recompiles the
    /// on-device blob at launch when its stamp differs, so app updates
    /// propagate new built-in rules without waiting for a list refresh.
    public static let version: UInt32 = 3

    public static let text = """
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
    """

    public static let relayText = """
    ! Apple tracker-relay / Private Relay endpoints.
    ! Blocking these forces tracker connections back onto on-device DNS,
    ! where the filter can see them. See SeedRules documentation.
    ||mask.icloud.com^
    ||mask-h2.icloud.com^
    ||mask-canary.icloud.com^
    ||doh.dns.apple.com^
    ||apple-relay.apple.com^
    ||apple-relay.cloudflare.com^
    ||apple-relay.fastly-edge.com^
    """

    public static let bundledTexts: [String: String] = [
        sourceID: text,
        relaySourceID: relayText,
    ]

    public static func bundledText(for sourceID: String) -> String? {
        bundledTexts[sourceID]
    }

    /// Hashes of the core rules (kept for tests and tooling).
    public static let blockHashes: Set<UInt64> = hashes(of: text)

    static func hashes(of ruleText: String) -> Set<UInt64> {
        Set(FilterListParser.parse(ruleText).blockDomains.map(FNV1a.hash64))
    }

    /// The tunnel's in-memory floor: the union of every bundled source the
    /// user has left enabled. Sources absent from saved state count as
    /// enabled (fresh installs, upgrades from older versions).
    public static func fallbackHashes(state: FilterListState) -> Set<UInt64> {
        var result: Set<UInt64> = []
        for (id, ruleText) in bundledTexts {
            let enabled = state.sources.first { $0.id == id }?.enabled ?? true
            if enabled {
                result.formUnion(hashes(of: ruleText))
            }
        }
        return result
    }
}
