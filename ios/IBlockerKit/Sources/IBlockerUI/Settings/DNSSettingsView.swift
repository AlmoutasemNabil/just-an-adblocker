#if os(iOS)
import SwiftUI
import NetworkExtension
import IBlockerKit

/// Third blocking mode: NEDNSSettingsManager installs an encrypted-DNS
/// setting directly (no profile file). The user still has to activate it in
/// Settings ▸ General ▸ VPN & Device Management once saved.
struct DNSSettingsView: View {
    @State private var isSaved = false
    @State private var isEnabled = false
    @State private var selectedPresetID = DNSProviderPreset.adguard.id
    @State private var message: String?
    @State private var busy = false

    var body: some View {
        Form {
            Section {
                Picker("Provider", selection: $selectedPresetID) {
                    ForEach(DNSProviderPreset.all) { preset in
                        Text(preset.name).tag(preset.id)
                    }
                }
                Button {
                    Task { await save() }
                } label: {
                    HStack {
                        Label("Set as system DNS", systemImage: "checkmark.shield")
                        Spacer()
                        if busy { ProgressView() }
                    }
                }
                .disabled(busy)

                if isSaved {
                    Button(role: .destructive) {
                        Task { await remove() }
                    } label: {
                        Label("Remove system DNS setting", systemImage: "trash")
                    }
                    .disabled(busy)
                }
            } header: {
                Text("Encrypted system DNS")
            } footer: {
                Text(statusText)
            }

            if let message {
                Section {
                    Text(message).foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle("System DNS")
        .task { await refresh() }
    }

    private var statusText: String {
        if isSaved && isEnabled {
            return "Active. All apps resolve through the selected provider whenever the VPN is off."
        }
        if isSaved {
            return "Saved, but not active yet — enable it in Settings ▸ General ▸ VPN & Device Management ▸ DNS."
        }
        return "Uses the selected provider for every app, without a VPN. The provider does the ad blocking (pick AdGuard for that)."
    }

    private func refresh() async {
        do {
            try await NEDNSSettingsManager.shared().loadFromPreferences()
            isSaved = NEDNSSettingsManager.shared().dnsSettings != nil
            isEnabled = NEDNSSettingsManager.shared().isEnabled
        } catch {
            message = error.localizedDescription
        }
    }

    private func save() async {
        guard let preset = DNSProviderPreset.all.first(where: { $0.id == selectedPresetID }) else { return }
        busy = true
        defer { busy = false }
        do {
            let manager = NEDNSSettingsManager.shared()
            try await manager.loadFromPreferences()
            switch preset.transport {
            case .https:
                let settings = NEDNSOverHTTPSSettings(servers: preset.addresses)
                settings.serverURL = preset.serverURL.flatMap(URL.init(string:))
                manager.dnsSettings = settings
            case .tls:
                let settings = NEDNSOverTLSSettings(servers: preset.addresses)
                settings.serverName = preset.serverName
                manager.dnsSettings = settings
            }
            try await manager.saveToPreferences()
            message = nil
            await refresh()
        } catch {
            message = error.localizedDescription
        }
    }

    private func remove() async {
        busy = true
        defer { busy = false }
        do {
            let manager = NEDNSSettingsManager.shared()
            try await manager.loadFromPreferences()
            try await manager.removeFromPreferences()
            message = nil
            await refresh()
        } catch {
            message = error.localizedDescription
        }
    }
}
#endif
