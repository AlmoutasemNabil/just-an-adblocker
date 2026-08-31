#if os(iOS)
import SwiftUI
import IBlockerKit

public struct OnboardingView: View {
    @Environment(TunnelController.self) private var tunnel
    @Environment(FilterListsViewModel.self) private var lists
    @Binding var isPresented: Bool
    @State private var step = 0
    @State private var downloadDone = false

    public init(isPresented: Binding<Bool>) {
        _isPresented = isPresented
    }

    public var body: some View {
        VStack {
            TabView(selection: $step) {
                explainer.tag(0)
                download.tag(1)
                enable.tag(2)
            }
            .tabViewStyle(.page)
            .indexViewStyle(.page(backgroundDisplayMode: .always))
        }
        .background(DashboardBackground())
        .interactiveDismissDisabled()
    }

    private var explainer: some View {
        OnboardingPage(
            symbol: "shield.lefthalf.filled",
            title: "Block ads everywhere",
            text: """
            AdBlocker runs a tiny VPN that never leaves your device. \
            It looks at one thing only — DNS lookups — and answers the ones \
            that belong to ad and tracking networks with "nothing here".

            Every app benefits, not just Safari. No subscription, no account, \
            no traffic sent anywhere.
            """
        ) {
            Button("Continue") { withAnimation { step = 1 } }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
        }
    }

    private var download: some View {
        OnboardingPage(
            symbol: "arrow.down.circle",
            title: "Get the blocklist",
            text: """
            AdBlocker uses OISD — a well-maintained list that blocks ads and \
            trackers without breaking apps or sites. You can add more lists later.
            """
        ) {
            if lists.isUpdating {
                ProgressView("Downloading…")
            } else if downloadDone {
                Label("\((lists.lastCompileStats?.blockedEntryCount ?? 0).formatted()) rules ready",
                      systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Button("Continue") { withAnimation { step = 2 } }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            } else {
                Button("Download blocklist") {
                    Task {
                        await lists.updateAndCompile(force: true)
                        downloadDone = (lists.lastCompileStats?.blockedEntryCount ?? 0) > 0
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                if let error = lists.errorMessage {
                    Text(error).font(.caption).foregroundStyle(.orange)
                }
            }
        }
    }

    private var enable: some View {
        OnboardingPage(
            symbol: "power.circle",
            title: "Turn it on",
            text: """
            iOS will ask permission to add a VPN configuration — that's the \
            on-device filter. Nothing is routed through third-party servers; \
            only DNS lookups are inspected, locally.
            """
        ) {
            if tunnel.state == .connected {
                Label("Protection is on", systemImage: "checkmark.shield.fill")
                    .foregroundStyle(.green)
                Button("Done") {
                    AppEnvironment.settings.onboardingComplete = true
                    isPresented = false
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            } else {
                Button("Enable protection") {
                    Task { await tunnel.enable() }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                if let error = tunnel.lastError {
                    Text(error).font(.caption).foregroundStyle(.orange)
                }
                Button("Skip for now") {
                    AppEnvironment.settings.onboardingComplete = true
                    isPresented = false
                }
                .buttonStyle(.borderless)
            }
        }
    }
}

private struct OnboardingPage<Actions: View>: View {
    let symbol: String
    let title: String
    let text: String
    @ViewBuilder var actions: Actions

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: symbol)
                .font(.system(size: 72))
                .foregroundStyle(.tint)
            Text(title)
                .font(.largeTitle.bold())
                .multilineTextAlignment(.center)
            Text(text)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 28)
            Spacer()
            VStack(spacing: 12) { actions }
            Spacer().frame(height: 60)
        }
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
    }
}
#endif
