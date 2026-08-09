#if os(iOS)
import SwiftUI
import SafariServices
import IBlockerKit

public struct SettingsView: View {
    @Environment(TunnelController.self) private var tunnel
    @Environment(FilterListsViewModel.self) private var lists
    @State private var upstream = AppEnvironment.settings.upstreamConfig
    @State private var logEnabled = AppEnvironment.settings.queryLogEnabled
    @State private var relayStrategy = AppEnvironment.settings.relayStrategy
    @State private var customDoHURL = ""
    @State private var customUDPAddress = ""
    @State private var blockerStatus: String?
    @State private var blockerBusy = false

    public init() {}

    /// Upstreams usable from inside the tunnel (IP-literal endpoints only —
    /// hostname DoH would recurse through the tunnel's own resolver).
    private static let upstreamChoices: [(name: String, config: UpstreamConfig)] = [
        ("Cloudflare (DoH)", UpstreamConfig(kind: .doh, dohURL: "https://1.1.1.1/dns-query")),
        ("AdGuard DNS (DoH, extra blocking)", UpstreamConfig(kind: .doh, dohURL: "https://94.140.14.14/dns-query")),
        ("Quad9 (DoH, malware blocking)", UpstreamConfig(kind: .doh, dohURL: "https://9.9.9.9:5053/dns-query")),
        ("Cloudflare (UDP 53)", UpstreamConfig(kind: .udp, udpAddress: "1.1.1.1")),
        ("Quad9 (UDP 53)", UpstreamConfig(kind: .udp, udpAddress: "9.9.9.9")),
    ]

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        BlockingTestView()
                    } label: {
                        Label("Verify blocking (in-app ad test)", systemImage: "checkmark.seal")
                    }
                } footer: {
                    Text("Resolves the Google in-app ad domains through the live tunnel and shows whether an ad SDK could reach them.")
                }

                Section {
                    Picker("Relay handling", selection: relayStrategyBinding) {
                        Text("Auto-suspend while protected").tag(RelayStrategy.autoSuspend)
                        Text("Block relay domains").tag(RelayStrategy.blockDomains)
                        Text("Keep Private Relay").tag(RelayStrategy.keepRelay)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Apple Private Relay")
                } footer: {
                    Text(relayFooter)
                }

                Section {
                    ForEach(Self.upstreamChoices, id: \.name) { choice in
                        HStack {
                            Text(choice.name)
                            Spacer()
                            if upstream == choice.config {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { apply(choice.config) }
                    }
                    DisclosureGroup("Custom upstream") {
                        HStack {
                            TextField("https://10.0.0.1/dns-query", text: $customDoHURL)
                                .keyboardType(.URL)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                            Button("Set DoH") {
                                if let _ = URL(string: customDoHURL) {
                                    apply(UpstreamConfig(kind: .doh, dohURL: customDoHURL))
                                }
                            }
                            .disabled(customDoHURL.isEmpty)
                        }
                        HStack {
                            TextField("9.9.9.11", text: $customUDPAddress)
                                .keyboardType(.numbersAndPunctuation)
                                .autocorrectionDisabled()
                            Button("Set UDP") {
                                apply(UpstreamConfig(kind: .udp, udpAddress: customUDPAddress))
                            }
                            .disabled(customUDPAddress.isEmpty)
                        }
                    }
                } header: {
                    Text("Upstream DNS (VPN mode)")
                } footer: {
                    Text("Where non-blocked queries are resolved. Use IP-literal endpoints only — hostnames can't be resolved from inside the tunnel.")
                }

                Section {
                    NavigationLink {
                        ProfileExportView()
                    } label: {
                        Label("Export DNS profile (.mobileconfig)", systemImage: "doc.badge.gearshape")
                    }
                    NavigationLink {
                        DNSSettingsView()
                    } label: {
                        Label("System encrypted DNS", systemImage: "network.badge.shield.half.filled")
                    }
                } header: {
                    Text("Blocking without the VPN")
                } footer: {
                    Text("Both alternatives block ads at a public DNS service instead of on-device. While the VPN is connected, its DNS wins; the profile or system DNS takes over whenever the VPN is off.")
                }

                Section {
                    Button {
                        refreshContentBlocker()
                    } label: {
                        HStack {
                            Label("Update Safari content blocker", systemImage: "safari")
                            Spacer()
                            if blockerBusy { ProgressView() }
                        }
                    }
                    .disabled(blockerBusy)
                    if let blockerStatus {
                        Text(blockerStatus)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Safari")
                } footer: {
                    Text("Downloads EasyList and converts it for Safari's built-in blocker (hides in-page ad leftovers). Enable it in Settings ▸ Apps ▸ Safari ▸ Extensions.")
                }

                Section("Privacy") {
                    Toggle("Keep a query log", isOn: $logEnabled)
                        .onChange(of: logEnabled) { _, value in
                            AppEnvironment.settings.queryLogEnabled = value
                        }
                    Text("The log never leaves this device. Turning it off takes effect the next time the tunnel starts.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("About") {
                    LabeledContent("Version", value: appVersion)
                    Text("IBlocker runs entirely on-device: a local VPN inspects only DNS lookups and answers blocked ones itself. No accounts, no subscriptions, no telemetry.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
    }

    private var relayStrategyBinding: Binding<RelayStrategy> {
        Binding(
            get: { relayStrategy },
            set: { strategy in
                relayStrategy = strategy
                AppEnvironment.settings.relayStrategy = strategy
                Task {
                    await lists.setRelayBlock(enabled: strategy == .blockDomains)
                    // Route presentation only applies at tunnel start.
                    if tunnel.isOn {
                        await tunnel.disable()
                        await tunnel.enable()
                    }
                }
            }
        )
    }

    private var relayFooter: String {
        switch relayStrategy {
        case .autoSuspend:
            return """
            The tunnel presents itself as a full VPN, so iOS pauses Private Relay and \
            tracker relaying by itself while protection runs — no Apple domains blocked, \
            no Settings changes needed. The relay returns the moment protection stops. \
            (This is how commercial blockers behave.) Changing this restarts the tunnel.
            """
        case .blockDomains:
            return """
            The relay endpoints are DNS-blocked while protection is on. Strongest \
            guarantee; iCloud Private Relay reports "unavailable" until protection stops.
            """
        case .keepRelay:
            return """
            Nothing Apple is ever blocked and the relay stays fully functional. You must \
            turn OFF "Limit IP Address Tracking" yourself (Settings ▸ Wi-Fi ▸ ⓘ on each \
            network, and Settings ▸ Cellular ▸ Cellular Data Options) or apps' tracker \
            traffic rides Apple's relay past this filter. Safari ads stay blocked by the \
            content blocker either way.
            """
        }
    }

    private var appVersion: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(short) (\(build))"
    }

    private func apply(_ config: UpstreamConfig) {
        upstream = config
        AppEnvironment.settings.upstreamConfig = config
        Task { await tunnel.setUpstream(config) }
    }

    private func refreshContentBlocker() {
        blockerBusy = true
        blockerStatus = nil
        Task {
            do {
                let stats = try await ContentBlockerRefresher.refresh(paths: AppEnvironment.paths)
                blockerStatus = "Converted \(stats.totalRules.formatted()) rules (\(stats.blockRules) blocks, \(stats.scopedHidingRules + stats.genericHidingRules) hiding)."
            } catch {
                blockerStatus = "Failed: \(error.localizedDescription)"
            }
            blockerBusy = false
        }
    }
}
#endif
