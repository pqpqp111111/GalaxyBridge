import SwiftUI

@main
struct syncApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var syncManager = SleepSyncManager()

    init() {
        BackgroundSyncCoordinator.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView(syncManager: syncManager)
                .task {
                    BackgroundSyncCoordinator.scheduleNextRefresh()
                    await syncManager.performAutoSync(reason: "Auto sync on launch")
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                BackgroundSyncCoordinator.scheduleNextRefresh()
                Task {
                    await syncManager.performAutoSync(reason: "Auto sync on foreground")
                }
            } else if phase == .background {
                BackgroundSyncCoordinator.scheduleNextRefresh()
            }
        }
    }
}
