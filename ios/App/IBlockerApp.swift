import SwiftUI
import IBlockerUI

@main
struct IBlockerApp: App {
    init() {
        ListRefreshScheduler.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
