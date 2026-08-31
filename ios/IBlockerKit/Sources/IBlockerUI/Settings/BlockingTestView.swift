#if os(iOS)
import SwiftUI
import IBlockerKit

/// The acceptance test, in the app: resolves the canonical in-app ad
/// domains through the live system resolver and shows whether an ad SDK
/// could reach them right now.
struct BlockingTestView: View {

    struct ProbeItem: Identifiable {
        let host: String
        let label: String
        let expectBlocked: Bool
        var isRelayProbe = false
        /// True when the user chose to keep Apple's relay: the probe reports
        /// status but never fails the verdict.
        var informational = false
        var outcome: BlockingProbe.Outcome?

        var id: String { host }

        var passed: Bool? {
            guard let outcome else { return nil }
            if informational { return true }
            switch outcome {
            case .blocked, .unreachable:
                return expectBlocked
            case .resolved:
                return !expectBlocked
            }
        }

        var isRelayReachable: Bool {
            guard informational, let outcome else { return false }
            if case .resolved = outcome { return true }
            return false
        }
    }

    @Environment(TunnelController.self) private var tunnel
    @Environment(FilterListsViewModel.self) private var lists
    @State private var probes: [ProbeItem] = Self.defaultProbes

    /// The relay probes only make sense alongside the relay controls, so they
    /// travel with the same flag. Dropping them also removes the relay banners
    /// and the auto-suspend button, which key off `isRelayProbe`.
    static var defaultProbes: [ProbeItem] {
        var items: [ProbeItem] = [
            ProbeItem(host: "googleads.g.doubleclick.net", label: "Google AdMob ad server", expectBlocked: true),
            ProbeItem(host: "pagead2.googlesyndication.com", label: "Google ad delivery", expectBlocked: true),
            ProbeItem(host: "app-measurement.com", label: "Google ad measurement", expectBlocked: true),
            ProbeItem(host: "adservice.google.com", label: "Google ad service", expectBlocked: true),
        ]
        if FeatureFlags.showAppleRelayControls {
            items.append(ProbeItem(host: "mask.icloud.com", label: "Apple tracker relay (ad-leak path)", expectBlocked: true, isRelayProbe: true))
            items.append(ProbeItem(host: "apple-relay.fastly-edge.com", label: "Apple relay egress (ad-leak path)", expectBlocked: true, isRelayProbe: true))
        }
        items.append(ProbeItem(host: "apple.com", label: "Control — must NOT be blocked", expectBlocked: false))
        return items
    }
    @State private var isRunning = false

    /// The headline verdict is decided by the ad-domain probes (the actual
    /// measure). Relay rows report separately below — a reachable relay is a
    /// warning with an action, never a scary "NOT blocked" while ads are dead.
    private var verdict: Bool? {
        let adProbes = probes.filter { !$0.isRelayProbe }
        let results = adProbes.compactMap(\.passed)
        guard results.count == adProbes.count else { return nil }
        return results.allSatisfy { $0 }
    }

    /// Relay block is ON but the relay still resolved — usually a cached
    /// answer from before the block, occasionally iOS sidestepping DNS.
    private var relayDodgedBlock: Bool {
        probes.contains { $0.isRelayProbe && !$0.informational && $0.passed == false }
    }

    var body: some View {
        List {
            Section {
                banner
            }

            Section("Probes") {
                ForEach(probes) { probe in
                    HStack(spacing: 12) {
                        statusIcon(for: probe)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(probe.host)
                                .font(.callout.monospaced())
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Text(detailText(for: probe))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section("Tunnel engine") {
                LabeledContent("Active rules",
                               value: (tunnel.runtimeStats?.blocklistEntryCount ?? 0).formatted())
                Button {
                    Task {
                        await lists.compileOnly()
                        await tunnel.refreshStats()
                        await run()
                    }
                } label: {
                    Label("Recompile rules now", systemImage: "hammer")
                }
                .disabled(isRunning)
            }

            Section {
                Button {
                    Task { await run() }
                } label: {
                    HStack {
                        Label("Run test again", systemImage: "arrow.clockwise")
                        Spacer()
                        if isRunning { ProgressView() }
                    }
                }
                .disabled(isRunning)
            } footer: {
                Text("""
                Each probe resolves through the system DNS — the exact path every \
                app's ad SDK uses. "Blocked" means the tunnel answered with a \
                blackhole address, so in-app ads cannot load.
                """)
            }
        }
        .navigationTitle("Blocking Test")
        .task { await run() }
    }

    @ViewBuilder
    private var banner: some View {
        bannerContent
        if probes.contains(where: \.isRelayReachable) {
            Label("""
            Apple's relay is reachable (your choice). Make sure "Limit IP Address \
            Tracking" is OFF in Settings ▸ Wi-Fi ▸ ⓘ and Settings ▸ Cellular ▸ \
            Cellular Data Options — otherwise apps' tracker traffic bypasses the filter.
            """, systemImage: "info.circle")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        if relayDodgedBlock {
            VStack(alignment: .leading, spacing: 8) {
                Label("""
                Apple's relay answered despite the block — usually a cached address \
                from before the block was on (toggle protection off/on, or reboot \
                once). If it persists, Auto-suspend mode shuts the relay down at the \
                OS level instead of relying on DNS.
                """, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.orange)
                Button("Switch to Auto-suspend mode") {
                    Task { await switchToAutoSuspend() }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
        }
    }

    private func switchToAutoSuspend() async {
        AppEnvironment.settings.relayStrategy = .autoSuspend
        await lists.setRelayBlock(enabled: true)  // belt + braces: keep domain blocks too
        if tunnel.isOn {
            await tunnel.disable()
            await tunnel.enable()
        }
        await run()
    }

    @ViewBuilder
    private var bannerContent: some View {
        if tunnel.state != .connected {
            Label("Protection is off — turn it on first, then re-run.",
                  systemImage: "exclamationmark.shield")
                .foregroundStyle(.orange)
                .font(.callout.weight(.semibold))
        } else if let verdict {
            if verdict {
                Label("In-app Google ads are BLOCKED", systemImage: "checkmark.shield.fill")
                    .foregroundStyle(.green)
                    .font(.callout.weight(.bold))
            } else {
                Label("NOT fully blocked — check that protection is on and lists are compiled.",
                      systemImage: "xmark.shield.fill")
                    .foregroundStyle(.red)
                    .font(.callout.weight(.semibold))
            }
        } else {
            Label("Testing…", systemImage: "ellipsis")
                .foregroundStyle(.secondary)
        }
    }

    private func statusIcon(for probe: ProbeItem) -> some View {
        Group {
            if probe.isRelayReachable {
                Image(systemName: "info.circle.fill").foregroundStyle(.blue)
            } else {
                switch probe.passed {
                case .none:
                    ProgressView().controlSize(.small)
                case .some(true):
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                case .some(false) where probe.isRelayProbe:
                    // Warning, not failure: the relay row doesn't decide the verdict.
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                case .some(false):
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
                }
            }
        }
        .frame(width: 22)
    }

    private func detailText(for probe: ProbeItem) -> String {
        guard let outcome = probe.outcome else { return probe.label }
        if probe.isRelayReachable {
            return "\(probe.label) — reachable, allowed by your relay setting"
        }
        switch outcome {
        case .blocked:
            return "\(probe.label) — blocked (blackhole answer)"
        case .unreachable(let reason):
            return "\(probe.label) — unreachable (\(reason))"
        case .resolved(let addresses):
            if probe.isRelayProbe {
                return "\(probe.label) — still resolving (likely cached; see note above)"
            }
            return "\(probe.label) — resolves to \(addresses.first ?? "?")"
        }
    }

    private func run() async {
        guard !isRunning else { return }
        isRunning = true
        await tunnel.refreshStats()
        let relayBlockEnabled = lists.isRelayBlockEnabled
        for index in probes.indices {
            probes[index].outcome = nil
            if probes[index].isRelayProbe {
                probes[index].informational = !relayBlockEnabled
            }
        }
        for index in probes.indices {
            probes[index].outcome = await BlockingProbe.probe(host: probes[index].host)
        }
        isRunning = false
    }
}
#endif
