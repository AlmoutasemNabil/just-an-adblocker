#if os(iOS)
import Foundation
import Observation
import IBlockerKit

@MainActor
@Observable
public final class FilterListsViewModel {

    public private(set) var state: FilterListState
    public private(set) var isUpdating = false
    public private(set) var lastCompileStats: CompileStats?
    public var errorMessage: String?

    private let paths: AppGroupPaths

    public init(paths: AppGroupPaths) {
        self.paths = paths
        self.state = FilterListState.load(from: paths.filterStateURL)
    }

    public func metadata(for sourceID: String) -> FilterListMetadata {
        state.metadata[sourceID] ?? FilterListMetadata()
    }

    // MARK: - Sources

    public func setSource(id: String, enabled: Bool) async {
        guard let index = state.sources.firstIndex(where: { $0.id == id }) else { return }
        state.sources[index].enabled = enabled
        persist()
        await updateAndCompile(force: false)
    }

    /// Validates by test-downloading before accepting the source.
    public func addCustomSource(name: String, url: URL) async -> Bool {
        let id = "custom-" + UUID().uuidString.prefix(8).lowercased()
        var probe = FilterListState()
        probe.sources = [FilterListSource(id: id, name: name, url: url, enabled: true, isBuiltIn: false)]
        let updater = FilterListUpdater(paths: paths)
        let summary = await updater.update(state: &probe, force: true)
        guard summary.updatedSourceIDs.contains(id) else {
            errorMessage = summary.failedSourceIDs[id] ?? "Download failed"
            return false
        }
        state.sources.append(FilterListSource(id: id, name: name, url: url, enabled: true, isBuiltIn: false))
        state.metadata[id] = probe.metadata[id]
        persist()
        await compileOnly()
        return true
    }

    public func removeSource(id: String) async {
        guard let index = state.sources.firstIndex(where: { $0.id == id }), !state.sources[index].isBuiltIn else { return }
        state.sources.remove(at: index)
        state.metadata[id] = nil
        try? FileManager.default.removeItem(at: paths.cachedListURL(sourceID: id))
        persist()
        await compileOnly()
    }

    // MARK: - Allow / deny

    public func addAllow(_ domain: String) async {
        guard let normalized = DomainValidator.normalize(domain),
              !state.userAllowlist.contains(normalized) else { return }
        state.userAllowlist.append(normalized)
        persist()
        await compileOnly()
    }

    public func addDeny(_ domain: String) async {
        guard let normalized = DomainValidator.normalize(domain),
              !state.userDenylist.contains(normalized) else { return }
        state.userDenylist.append(normalized)
        persist()
        await compileOnly()
    }

    public func removeAllow(_ domain: String) async {
        state.userAllowlist.removeAll { $0 == domain }
        persist()
        await compileOnly()
    }

    public func removeDeny(_ domain: String) async {
        state.userDenylist.removeAll { $0 == domain }
        persist()
        await compileOnly()
    }

    // MARK: - Update & compile

    public var onRulesChanged: (@MainActor () async -> Void)?

    public func updateAndCompile(force: Bool) async {
        guard !isUpdating else { return }
        isUpdating = true
        defer { isUpdating = false }

        var working = state
        let updater = FilterListUpdater(paths: paths)
        let summary = await updater.update(state: &working, force: force)
        state = working

        if !summary.failedSourceIDs.isEmpty {
            let names = summary.failedSourceIDs.keys.sorted().joined(separator: ", ")
            errorMessage = "Update failed for: \(names)"
        } else {
            errorMessage = nil
        }

        await compileOnly()
        AppEnvironment.settings.lastListUpdate = Date()
    }

    /// Recompiles from cached list files off the main actor (large lists
    /// parse in the hundreds of milliseconds).
    public func compileOnly() async {
        let paths = self.paths
        let input = state
        let compiled: (FilterListState, CompileStats)? = await Task.detached(priority: .userInitiated) {
            var working = input
            guard let stats = try? BlocklistCompiler.compile(state: &working, paths: paths) else { return nil }
            return (working, stats)
        }.value

        if let (newState, stats) = compiled {
            state = newState
            lastCompileStats = stats
            persist()
            await onRulesChanged?()
        }
    }

    /// Recompiles immediately when the app's built-in rules changed since
    /// the on-device blob was produced (app updates), or the blob is
    /// missing. Cheap no-op otherwise. Closes the gap where an updated app
    /// ships new seed rules but the old blob keeps serving the tunnel.
    public func ensureFreshCompile() async {
        let blobMissing = !FileManager.default.fileExists(atPath: paths.blocklistURL.path)
        if blobMissing || state.compiledSeedVersion != SeedRules.version {
            await compileOnly()
        }
    }

    /// Foreground staleness check: BGAppRefresh is best-effort, so refresh
    /// whenever the app comes forward with lists older than a day.
    public func refreshIfStale() async {
        let last = AppEnvironment.settings.lastListUpdate ?? .distantPast
        if Date().timeIntervalSince(last) > 24 * 3600 {
            await updateAndCompile(force: false)
        }
    }

    private func persist() {
        try? state.save(to: paths.filterStateURL)
    }
}
#endif
