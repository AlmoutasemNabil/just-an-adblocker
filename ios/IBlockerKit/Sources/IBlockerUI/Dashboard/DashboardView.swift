#if os(iOS)
import SwiftUI
import IBlockerKit

public struct DashboardView: View {
    @Environment(TunnelController.self) private var tunnel
    @Environment(QueryLogViewModel.self) private var log
    @State private var fileStats = BlockerStats()

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 28) {
                    ProtectionToggle()
                        .padding(.top, 24)

                    statusLine

                    pauseControls

                    NavigationLink {
                        BlockingTestView()
                    } label: {
                        Label("Verify ad blocking", systemImage: "checkmark.seal")
                            .font(.callout.weight(.medium))
                    }
                    .buttonStyle(.bordered)

                    countersRow

                    StatsChartView()

                    if let error = tunnel.lastError {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .padding(.horizontal)
                    }
                }
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 32)
            }
            .background(DashboardBackground())
            .navigationTitle("Just an AdBlocker")
            .task {
                await refreshLoop()
            }
        }
    }

    private var statusLine: some View {
        Group {
            switch tunnel.state {
            case .connected:
                Label("Protected — DNS filtering active", systemImage: "checkmark.shield.fill")
                    .foregroundStyle(.green)
            case .connecting:
                Label("Connecting…", systemImage: "arrow.triangle.2.circlepath")
                    .foregroundStyle(.secondary)
            case .disconnecting:
                Label("Stopping…", systemImage: "arrow.triangle.2.circlepath")
                    .foregroundStyle(.secondary)
            case .disconnected:
                Label("Not protected", systemImage: "shield.slash")
                    .foregroundStyle(.secondary)
            case .notInstalled:
                Label("Tap the shield to set up protection", systemImage: "hand.tap")
                    .foregroundStyle(.secondary)
            case .permissionNeeded:
                Label("VPN permission needed — tap the shield", systemImage: "lock.shield")
                    .foregroundStyle(.orange)
            case .unknown:
                Label("Checking status…", systemImage: "ellipsis")
                    .foregroundStyle(.secondary)
            }
        }
        .font(.callout.weight(.medium))
    }

    @ViewBuilder
    private var pauseControls: some View {
        if tunnel.state == .connected {
            if let until = tunnel.pausedUntil, until > Date() {
                VStack(spacing: 6) {
                    Label {
                        Text("Paused — resumes \(until, style: .relative)")
                    } icon: {
                        Image(systemName: "pause.circle.fill")
                    }
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.orange)

                    Button("Resume now") {
                        Task { await tunnel.resume() }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
            } else {
                Menu {
                    Button("Pause 5 minutes") { Task { await tunnel.pause(minutes: 5) } }
                    Button("Pause 15 minutes") { Task { await tunnel.pause(minutes: 15) } }
                    Button("Pause 1 hour") { Task { await tunnel.pause(minutes: 60) } }
                } label: {
                    Label("Pause blocking", systemImage: "pause.circle")
                        .font(.callout.weight(.medium))
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var countersRow: some View {
        HStack(spacing: 12) {
            CounterTile(
                title: "Blocked today",
                value: todayBlocked,
                symbol: "nosign",
                tint: .red
            )
            CounterTile(
                title: "Total blocked",
                value: totalBlocked,
                symbol: "shield.fill",
                tint: .blue
            )
            CounterTile(
                title: "Rules",
                value: ruleCount,
                symbol: "list.number",
                tint: .purple
            )
        }
        .padding(.horizontal)
    }

    private var todayBlocked: UInt64 {
        fileStats.counters(day: BlockerStats.dayKey()).blocked
    }

    private var totalBlocked: UInt64 {
        max(tunnel.runtimeStats?.blockedQueries ?? 0, fileStats.totalBlocked)
    }

    private var ruleCount: UInt64 {
        if let live = tunnel.runtimeStats?.blocklistEntryCount, live > 0 { return live }
        return UInt64((try? CompiledBlocklistView(contentsOf: AppEnvironment.paths.blocklistURL))?.count ?? 0)
    }

    private func refreshLoop() async {
        while !Task.isCancelled {
            await tunnel.refreshStats()
            fileStats = StatsPersistence.load(from: AppEnvironment.paths.statsURL)
            try? await Task.sleep(nanoseconds: 3_000_000_000)
        }
    }
}

struct CounterTile: View {
    let title: String
    let value: UInt64
    let symbol: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: symbol)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(value.formatted(.number.notation(.compactName)))
                .font(.system(.title, design: .rounded, weight: .bold))
                .foregroundStyle(tint)
                .contentTransition(.numericText())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

struct DashboardBackground: View {
    var body: some View {
        LinearGradient(
            colors: [Color.blue.opacity(0.12), Color.purple.opacity(0.10), Color.clear],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }
}
#endif
