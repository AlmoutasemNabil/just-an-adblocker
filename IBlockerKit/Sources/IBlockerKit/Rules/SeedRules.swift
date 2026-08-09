import Foundation

/// The built-in "core mobile ad networks" ruleset, compiled into the binary.
///
/// This is the guaranteed blocking floor: it is merged at every compile even
/// when no filter list has ever been downloaded (first launch, offline,
/// list-server outage), so in-app ads — Google/AdMob above all — are blocked
/// from the moment the tunnel first starts.
///
/// Curation rules: only domains that (a) serve mobile in-app ads or their
/// measurement, and (b) are safe to block without hanging apps. Unity Ads
/// config hosts are deliberately absent (blocking them stalls some games);
/// the downloadable lists carve those out properly.
public enum SeedRules {

    public static let sourceID = "bundled-core"

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
}
