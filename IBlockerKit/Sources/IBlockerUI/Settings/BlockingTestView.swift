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
        var outcome: BlockingProbe.Outcome?

        var id: String { host }

        var passed: Bool? {
            guard let outcome else { return nil }
            switch outcome {
            case .blocked, .unreachable:
                return expectBlocked
            case .resolved:
                return !expectBlocked
            }
        }
    }

    @Environment(TunnelController.self) private var tunnel
    @Environment(FilterListsViewModel.self) private var lists
    @State private var probes: [ProbeItem] = [
        ProbeItem(host: "googleads.g.doubleclick.net", label: "Google AdMob ad server", expectBlocked: true),
        ProbeItem(host: "pagead2.googlesyndication.com", label: "Google ad delivery", expectBlocked: true),
        ProbeItem(host: "app-measurement.com", label: "Google ad measurement", expectBlocked: true),
        ProbeItem(host: "adservice.google.com", label: "Google ad service", expectBlocked: true),
        ProbeItem(host: "mask.icloud.com", label: "Apple tracker relay (ad-leak path)", expectBlocked: true),
        ProbeItem(host: "apple-relay.fastly-edge.com", label: "Apple relay egress (ad-leak path)", expectBlocked: true),
        ProbeItem(host: "apple.com", label: "Control — must NOT be blocked", expectBlocked: false),
    ]
    @State private var isRunning = false

    private var verdict: Bool? {
        let results = probes.compactMap(\.passed)
        guard results.count == probes.count else { return nil }
        return results.allSatisfy { $0 }
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
            switch probe.passed {
            case .none:
                ProgressView().controlSize(.small)
            case .some(true):
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            case .some(false):
                Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
            }
        }
        .frame(width: 22)
    }

    private func detailText(for probe: ProbeItem) -> String {
        guard let outcome = probe.outcome else { return probe.label }
        switch outcome {
        case .blocked:
            return "\(probe.label) — blocked (blackhole answer)"
        case .unreachable(let reason):
            return "\(probe.label) — unreachable (\(reason))"
        case .resolved(let addresses):
            return "\(probe.label) — resolves to \(addresses.first ?? "?")"
        }
    }

    private func run() async {
        guard !isRunning else { return }
        isRunning = true
        await tunnel.refreshStats()
        for index in probes.indices {
            probes[index].outcome = nil
        }
        for index in probes.indices {
            probes[index].outcome = await BlockingProbe.probe(host: probes[index].host)
        }
        isRunning = false
    }
}
#endif
