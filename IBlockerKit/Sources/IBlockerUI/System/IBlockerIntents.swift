#if os(iOS)
import AppIntents
import WidgetKit
import IBlockerKit

// MARK: - Protection on/off

struct EnableProtectionIntent: AppIntent {
    static var title: LocalizedStringResource = "Turn On Ad Blocking"
    static var description = IntentDescription("Starts IBlocker protection.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult {
        try await VPNControl.setEnabled(true)
        return .result()
    }
}

struct DisableProtectionIntent: AppIntent {
    static var title: LocalizedStringResource = "Turn Off Ad Blocking"
    static var description = IntentDescription("Stops IBlocker protection.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult {
        try await VPNControl.setEnabled(false)
        return .result()
    }
}

/// Powers the Control Center toggle. As a `SetValueIntent`, the system sets
/// `value` to the toggle's new state before calling `perform`.
struct SetProtectionIntent: SetValueIntent {
    static var title: LocalizedStringResource = "Set Ad Blocking"

    @Parameter(title: "Enabled")
    var value: Bool

    func perform() async throws -> some IntentResult {
        try await VPNControl.setEnabled(value)
        return .result()
    }
}

// MARK: - Pause

struct PauseProtectionIntent: AppIntent {
    static var title: LocalizedStringResource = "Pause Ad Blocking"
    static var description = IntentDescription("Lets everything through for a set number of minutes, then resumes on its own.")
    static var openAppWhenRun = false

    @Parameter(title: "Minutes", default: 5, controlStyle: .field, inclusiveRange: (1, 240))
    var minutes: Int

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VPNControl.pause(minutes: minutes)
        return .result(dialog: "Ad blocking paused for \(minutes) minutes.")
    }
}

struct ResumeProtectionIntent: AppIntent {
    static var title: LocalizedStringResource = "Resume Ad Blocking"
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult {
        try await VPNControl.resume()
        return .result()
    }
}

// MARK: - Lists

struct UpdateListsIntent: AppIntent {
    static var title: LocalizedStringResource = "Update Filter Lists"
    static var description = IntentDescription("Downloads the latest blocklists and recompiles the rules.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let paths = AppEnvironment.paths
        try? paths.ensureDirectories()
        var state = FilterListState.load(from: paths.filterStateURL)
        let summary = await FilterListUpdater(paths: paths).update(state: &state)
        let stats = try? BlocklistCompiler.compile(state: &state, paths: paths)
        try? state.save(to: paths.filterStateURL)
        AppEnvironment.settings.lastListUpdate = Date()
        VPNControl.reloadWidgets()
        let count = stats?.blockedEntryCount ?? 0
        if summary.failedSourceIDs.isEmpty {
            return .result(dialog: "Lists updated — \(count) rules active.")
        }
        return .result(dialog: "Lists updated with some errors — \(count) rules active.")
    }
}

// MARK: - Siri / Shortcuts phrases

struct IBlockerShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: EnableProtectionIntent(),
            phrases: ["Turn on \(.applicationName)", "Start \(.applicationName) blocking"],
            shortTitle: "Turn On",
            systemImageName: "shield.fill"
        )
        AppShortcut(
            intent: DisableProtectionIntent(),
            phrases: ["Turn off \(.applicationName)", "Stop \(.applicationName) blocking"],
            shortTitle: "Turn Off",
            systemImageName: "shield.slash"
        )
        AppShortcut(
            intent: PauseProtectionIntent(),
            phrases: ["Pause \(.applicationName)"],
            shortTitle: "Pause",
            systemImageName: "pause.circle"
        )
        AppShortcut(
            intent: UpdateListsIntent(),
            phrases: ["Update \(.applicationName) lists"],
            shortTitle: "Update Lists",
            systemImageName: "arrow.clockwise"
        )
    }
}
#endif
