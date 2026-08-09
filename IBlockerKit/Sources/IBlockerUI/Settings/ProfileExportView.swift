#if os(iOS)
import SwiftUI
import IBlockerKit

/// Generates a .mobileconfig DNS profile and hands it off via the share
/// sheet. iOS only installs profiles through Settings, so the flow is:
/// share → AirDrop/Files/Safari → Settings shows "Profile Downloaded".
struct ProfileExportView: View {
    @State private var selectedPresetID = DNSProviderPreset.adguard.id
    @State private var nextDNSConfigID = ""
    @State private var exportedFileURL: URL?
    @State private var exportError: String?

    private var selectedPreset: DNSProviderPreset? {
        if selectedPresetID == "nextdns" {
            let trimmed = nextDNSConfigID.trimmingCharacters(in: .whitespaces)
            return trimmed.isEmpty ? nil : .nextDNS(configID: trimmed)
        }
        return DNSProviderPreset.all.first { $0.id == selectedPresetID }
    }

    var body: some View {
        Form {
            Section {
                Picker("Provider", selection: $selectedPresetID) {
                    ForEach(DNSProviderPreset.all) { preset in
                        VStack(alignment: .leading) {
                            Text(preset.name)
                            Text(preset.detail)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .tag(preset.id)
                    }
                    Text("NextDNS (your config)").tag("nextdns")
                }
                .pickerStyle(.inline)
                .labelsHidden()

                if selectedPresetID == "nextdns" {
                    TextField("NextDNS config ID (e.g. abc123)", text: $nextDNSConfigID)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            } header: {
                Text("DNS provider")
            } footer: {
                Text("Pick a provider that blocks ads (AdGuard, Mullvad ad-blocking, NextDNS) if this profile is your main blocking mode.")
            }

            Section {
                Button {
                    export()
                } label: {
                    Label("Generate profile", systemImage: "square.and.arrow.up")
                }
                .disabled(selectedPreset == nil)

                if let url = exportedFileURL {
                    ShareLink(item: url) {
                        Label("Share \(url.lastPathComponent)", systemImage: "square.and.arrow.up.on.square")
                    }
                }
                if let exportError {
                    Text(exportError).foregroundStyle(.orange)
                }
            } footer: {
                Text("""
                To install: share the file to yourself (AirDrop or Files), open it, \
                then go to Settings ▸ General ▸ VPN & Device Management ▸ Profile Downloaded and tap Install.
                """)
            }
        }
        .navigationTitle("DNS Profile")
    }

    private func export() {
        guard let preset = selectedPreset else { return }
        do {
            let data = MobileConfigBuilder.profile(for: preset)
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("IBlocker-\(preset.id).mobileconfig")
            try data.write(to: url, options: .atomic)
            exportedFileURL = url
            exportError = nil
        } catch {
            exportError = error.localizedDescription
        }
    }
}
#endif
