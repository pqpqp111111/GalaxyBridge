import BackgroundTasks
import Foundation
import UIKit

enum BackgroundSyncCoordinator {
    static let refreshIdentifier = "com.example.galaxybridge.sync.refresh"

    static func register() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: refreshIdentifier,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }

            handle(refreshTask)
        }
    }

    static func scheduleNextRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // The system may reject scheduling when Background App Refresh is disabled.
        }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        scheduleNextRefresh()

        let syncTask = Task {
            let manager = SleepSyncManager()
            let success = await manager.performAutoSync(
                reason: "Background auto sync",
                minimumInterval: 0
            )
            task.setTaskCompleted(success: success)
        }

        task.expirationHandler = {
            syncTask.cancel()
            task.setTaskCompleted(success: false)
        }
    }
}
