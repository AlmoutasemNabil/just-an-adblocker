#if os(iOS)
import Foundation
import IBlockerKit

/// Process-wide accessors resolved from the app's Info.plist, so no source
/// file hardcodes the App Group or bundle identifiers.
public enum AppEnvironment {
    public static var groupID: String {
        AppGroupPaths.groupID(from: .main) ?? "group.invalid.iblocker"
    }

    public static var paths: AppGroupPaths {
        AppGroupPaths(groupID: groupID)
            ?? AppGroupPaths(containerURL: FileManager.default.temporaryDirectory)
    }

    public static var settings: SharedSettings {
        SharedSettings(groupID: groupID) ?? SharedSettings(defaults: .standard)
    }

    public static var refreshTaskID: String {
        (Bundle.main.bundleIdentifier ?? "iblocker") + ".refresh"
    }

    public static var tunnelBundleID: String {
        (Bundle.main.bundleIdentifier ?? "iblocker") + ".tunnel"
    }

    public static var contentBlockerBundleID: String {
        (Bundle.main.bundleIdentifier ?? "iblocker") + ".blocker"
    }
}
#endif
