#if os(iOS)
import Foundation
import BackgroundTasks
import IBlockerKit

/// Best-effort background list refresh. iOS decides when (and whether) the
/// task runs, so the foreground staleness check in FilterListsViewModel is
/// the primary refresh path; this is the bonus.
public enum ListRefreshScheduler {

    /// Must be called before the app finishes launching (App.init).
    public static func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: AppEnvironment.refreshTaskID,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(refreshTask)
        }
        schedule()
    }

    public static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: AppEnvironment.refreshTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func handle(_ task: BGAppRefreshTask) {
        let work = Task {
            let paths = AppEnvironment.paths
            try? paths.ensureDirectories()
            var state = FilterListState.load(from: paths.filterStateURL)
            let updater = FilterListUpdater(paths: paths)
            let summary = await updater.update(state: &state)
            if summary.anyChanged {
                _ = try? BlocklistCompiler.compile(state: &state, paths: paths)
                AppEnvironment.settings.lastListUpdate = Date()
            }
            try? state.save(to: paths.filterStateURL)
            schedule()
            task.setTaskCompleted(success: summary.failedSourceIDs.isEmpty)
        }
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }
}
#endif
