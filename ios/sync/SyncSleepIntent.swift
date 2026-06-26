import AppIntents

struct SyncSleepIntent: AppIntent {
    static var title: LocalizedStringResource = "Sync GalaxyBridge Sleep"
    static var description = IntentDescription("Fetches sleep data from the Android GalaxyBridge server and writes it to Apple Health.")
    static var openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let manager = SleepSyncManager()

        guard manager.hasServerConfiguration else {
            return .result(dialog: "Set the Android Server IP in GalaxyBridge before syncing.")
        }

        let success = await manager.fetchAndSyncSleep()
        if success {
            return .result(dialog: "GalaxyBridge sleep sync completed.")
        } else {
            return .result(dialog: "GalaxyBridge sleep sync failed. Open GalaxyBridge to see details.")
        }
    }
}

struct GalaxyBridgeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SyncSleepIntent(),
            phrases: [
                "Sync sleep with \(.applicationName)",
                "Run \(.applicationName) sleep sync",
                "Import Galaxy sleep with \(.applicationName)"
            ],
            shortTitle: "Sync Sleep",
            systemImageName: "arrow.triangle.2.circlepath"
        )
    }
}
