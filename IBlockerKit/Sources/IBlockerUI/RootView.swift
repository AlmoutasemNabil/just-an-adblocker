#if os(iOS)
import SwiftUI
import IBlockerKit

public struct RootView: View {
    @State private var tunnel = TunnelController()
    @State private var lists: FilterListsViewModel
    @State private var log: QueryLogViewModel
    @State private var showOnboarding: Bool
    @Environment(\.scenePhase) private var scenePhase

    public init() {
        let paths = AppEnvironment.paths
        try? paths.ensureDirectories()
        _lists = State(initialValue: FilterListsViewModel(paths: paths))
        _log = State(initialValue: QueryLogViewModel(paths: paths))
        _showOnboarding = State(initialValue: !AppEnvironment.settings.onboardingComplete)
    }

    public var body: some View {
        TabView {
            Tab("Dashboard", systemImage: "shield.lefthalf.filled") {
                DashboardView()
            }
            Tab("Log", systemImage: "list.bullet.rectangle.portrait") {
                QueryLogView()
            }
            Tab("Lists", systemImage: "checklist") {
                FilterListsView()
            }
            Tab("Settings", systemImage: "gearshape") {
                SettingsView()
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .environment(tunnel)
        .environment(lists)
        .environment(log)
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingView(isPresented: $showOnboarding)
                .environment(tunnel)
                .environment(lists)
        }
        .task {
            lists.onRulesChanged = { [weak tunnel] in
                await tunnel?.reloadRules()
            }
            await lists.ensureFreshCompile()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await tunnel.refresh()
                    await lists.refreshIfStale()
                }
                ListRefreshScheduler.schedule()
            }
        }
    }
}
#endif
