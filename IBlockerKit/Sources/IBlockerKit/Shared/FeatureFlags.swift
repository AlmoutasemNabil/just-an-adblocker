import Foundation

/// Compile-time switches for finished features that are deliberately not
/// surfaced. The implementation stays live and tested; only the UI is gated.
public enum FeatureFlags {

    /// Apple relay handling — Private Relay and "Limit IP Address Tracking".
    ///
    /// Hidden because ad and tracker blocking does not depend on it: the
    /// bundled relay list ships disabled, and every ad-domain probe passes
    /// without it. Surfacing a control that blocks an Apple privacy feature
    /// buys nothing for the user and invites App Review questions.
    ///
    /// Flipping this to `true` restores, together:
    ///   - the "Block Apple tracker relay" row in Filter Lists
    ///   - the "Apple Private Relay" strategy picker in Settings
    ///   - the two relay probes and their notes in the Blocking Test
    ///   - `SharedSettings.relayStrategy` honouring its stored value
    ///
    /// While `false`, `relayStrategy` reports `.blockDomains` regardless of
    /// what is stored, so a value chosen before the UI was hidden can't strand
    /// the tunnel in a mode with no way back.
    public static let showAppleRelayControls = false

    /// HaGeZi Multi Pro. Held back while its feed is being verified; the
    /// source definition, its rules, and its parsing stay in place.
    public static let showHaGeZiList = false

    /// Sources that must not appear, fetch, compile, or match while hidden.
    /// A hidden source that still downloads would keep recording errors no
    /// one can see or act on.
    public static var hiddenSourceIDs: Set<String> {
        var ids: Set<String> = []
        if !showAppleRelayControls { ids.insert(SeedRules.relaySourceID) }
        if !showHaGeZiList { ids.insert(FilterListSource.hageziID) }
        return ids
    }

    public static func isHidden(sourceID: String) -> Bool {
        hiddenSourceIDs.contains(sourceID)
    }
}
