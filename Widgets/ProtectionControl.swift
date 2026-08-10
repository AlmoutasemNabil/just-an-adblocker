import WidgetKit
import AppIntents
import SwiftUI
import NetworkExtension
import IBlockerKit

/// Control Center toggle (iOS 18+). State comes from the shared
/// `protectionActive` mirror; flipping it runs `ControlProtectionIntent`,
/// which the system fills with the new value.
@available(iOS 18.0, *)
struct ProtectionControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: WidgetKinds.protectionControl, provider: Provider()) { isOn in
            ControlWidgetToggle(
                "Ad Blocking",
                isOn: isOn,
                action: ControlProtectionIntent()
            ) { on in
                Label(on ? "Blocking" : "Off",
                      systemImage: on ? "shield.fill" : "shield.slash")
            }
            .tint(.green)
        }
        .displayName("IBlocker")
        .description("Turn ad blocking on or off.")
    }

    struct Provider: ControlValueProvider {
        var previewValue: Bool { true }

        func currentValue() async throws -> Bool {
            WidgetEnvironment.settings?.protectionActive ?? false
        }
    }
}

/// The control toggle's action. Lives in the widget target because the
/// Control Center widget runs in the extension. Starts/stops the existing
/// tunnel via NetworkExtension (available to app extensions).
struct ControlProtectionIntent: SetValueIntent {
    static var title: LocalizedStringResource = "Set Ad Blocking"

    @Parameter(title: "Enabled")
    var value: Bool

    func perform() async throws -> some IntentResult {
        let managers = try await NETunnelProviderManager.loadAllFromPreferences()
        guard let manager = managers.first else { return .result() }
        if value {
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
        WidgetEnvironment.settings?.protectionActive = value
        return .result()
    }
}
