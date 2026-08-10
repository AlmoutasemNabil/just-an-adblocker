#if os(iOS)
import Foundation
import NetworkExtension
import WidgetKit
import IBlockerKit

/// Process-independent VPN control used by App Intents and the Control
/// Center toggle, which run outside the app's `TunnelController`. Every
/// entry point also mirrors state into shared defaults and nudges the
/// widgets/controls to redraw.
public enum VPNControl {

    public enum ControlError: LocalizedError {
        case notConfigured

        public var errorDescription: String? {
            switch self {
            case .notConfigured:
                return "Open IBlocker once to set up protection before controlling it here."
            }
        }
    }

    /// The existing IBlocker tunnel manager, if the user has set it up.
    static func loadManager() async throws -> NETunnelProviderManager? {
        try await NETunnelProviderManager.loadAllFromPreferences().first
    }

    public static func setEnabled(_ enabled: Bool) async throws {
        guard let manager = try await loadManager() else { throw ControlError.notConfigured }
        if enabled {
            manager.isEnabled = true
            manager.isOnDemandEnabled = true
            try await manager.saveToPreferences()
            try await manager.loadFromPreferences()
            try manager.connection.startVPNTunnel()
        } else {
            manager.isOnDemandEnabled = false
            try await manager.saveToPreferences()
            manager.connection.stopVPNTunnel()
        }
        AppEnvironment.settings.protectionActive = enabled
        reloadWidgets()
    }

    /// Suspends blocking for `minutes` (blocking resumes automatically).
    /// Writes shared state and, if the tunnel is live, notifies it over IPC
    /// so the pause takes effect immediately rather than on the next tick.
    public static func pause(minutes: Int) async throws {
        let until = Date(timeIntervalSinceNow: TimeInterval(minutes * 60))
        AppEnvironment.settings.pausedUntil = until
        try? await sendPause(until: until)
        reloadWidgets()
    }

    public static func resume() async throws {
        AppEnvironment.settings.pausedUntil = nil
        try? await sendPause(until: nil)
        reloadWidgets()
    }

    private static func sendPause(until: Date?) async throws {
        guard let session = try await loadManager()?.connection as? NETunnelProviderSession,
              session.status == .connected else { return }
        let message = try TunnelIPCCoder.encode(.setPause(until: until))
        try session.sendProviderMessage(message, responseHandler: nil)
    }

    static func reloadWidgets() {
        WidgetCenter.shared.reloadAllTimelines()
        if #available(iOS 18.0, *) {
            ControlCenter.shared.reloadControls(ofKind: WidgetKinds.protectionControl)
        }
    }
}
#endif
