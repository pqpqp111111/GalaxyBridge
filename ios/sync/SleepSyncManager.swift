import Foundation
import HealthKit
import Combine

class SleepSyncManager: ObservableObject {
    @Published var status: String = "Ready"
    @Published var isLoading: Bool = false

    private let healthStore = HKHealthStore()
    private var serverURL: String
    private var lastSyncAttempt: Date?

    init() {
        let ip = UserDefaults.standard.string(forKey: "serverIP") ?? ""
        let port = UserDefaults.standard.string(forKey: "port") ?? "8787"
        self.serverURL = "http://\(ip):\(port)"
    }

    func updateServer(ip: String, port: String) {
        self.serverURL = "http://\(ip):\(port)"
    }

    var hasServerConfiguration: Bool {
        guard let components = URLComponents(string: serverURL) else {
            return false
        }
        return components.host?.isEmpty == false
    }

    func requestHealthKitPermissions() async {
        guard HKHealthStore.isHealthDataAvailable() else {
            await MainActor.run { status = "HealthKit not available on this device" }
            return
        }

        let sleepType = HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!

        do {
            try await healthStore.requestAuthorization(toShare: [sleepType], read: [sleepType])
            await MainActor.run { status = "HealthKit permissions granted" }
        } catch {
            await MainActor.run { status = "Permission error: \(error.localizedDescription)" }
        }
    }

    @discardableResult
    func fetchAndSyncSleep() async -> Bool {
        guard hasServerConfiguration else {
            await MainActor.run {
                status = "Set Android Server IP before syncing."
            }
            return false
        }

        await MainActor.run {
            isLoading = true
            status = "Connecting to \(serverURL)..."
        }

        do {
            let sleepData = try await fetchSleepData()
            await MainActor.run {
                status = "Writing \(sleepData.sleep.count) records to HealthKit..."
            }
            try await writeSleepToHealthKit(sleepData)
            await MainActor.run {
                status = "Done! \(sleepData.sleep.count) sleep records synced."
                isLoading = false
            }
            return true
        } catch {
            await MainActor.run {
                status = "Error: \(error.localizedDescription)"
                isLoading = false
            }
            return false
        }
    }

    @discardableResult
    func performAutoSync(reason: String, minimumInterval: TimeInterval = 15 * 60) async -> Bool {
        guard hasServerConfiguration else {
            await MainActor.run {
                status = "Auto sync skipped: Android Server IP is not set."
            }
            return false
        }

        if let lastSyncAttempt, Date().timeIntervalSince(lastSyncAttempt) < minimumInterval {
            return true
        }

        lastSyncAttempt = Date()
        await MainActor.run {
            status = "\(reason)..."
        }
        return await fetchAndSyncSleep()
    }

    private func fetchSleepData() async throws -> SyncResponse {
        let urlString = "\(serverURL)/sync/sleep?since=0"
        guard let url = URL(string: urlString) else {
            throw SyncError.invalidURL
        }

        var request = URLRequest(url: url)
        request.setValue("change-me", forHTTPHeaderField: "X-Bridge-Token")
        request.timeoutInterval = 10

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw SyncError.serverError
        }

        guard httpResponse.statusCode == 200 else {
            throw SyncError.serverError
        }

        do {
            return try JSONDecoder().decode(SyncResponse.self, from: data)
        } catch let error as DecodingError {
            let body = String(data: data, encoding: .utf8) ?? "<non-utf8 response>"
            throw SyncError.decodingFailed(Self.describeDecodingError(error, body: body))
        }
    }

    private func writeSleepToHealthKit(_ response: SyncResponse) async throws {
        let sleepType = HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!

        for sleepRecord in response.sleep {
            for stage in sleepRecord.stages {
                let startDate = Date(timeIntervalSince1970: Double(stage.start) / 1000.0)
                let endDate = Date(timeIntervalSince1970: Double(stage.end) / 1000.0)

                guard let sleepValue = mapStageToHealthKit(stage.stage) else {
                    continue
                }

                let sample = HKCategorySample(
                    type: sleepType,
                    value: sleepValue.rawValue,
                    start: startDate,
                    end: endDate,
                    metadata: [
                        "source": "GalaxyBridge",
                        "originalId": sleepRecord.id
                    ]
                )

                try await healthStore.save(sample)
            }
        }
    }

    private func mapStageToHealthKit(_ stage: Int) -> HKCategoryValueSleepAnalysis? {
        switch stage {
        case 1:  return .awake
        case 2:  return .asleepUnspecified
        case 3:  return .inBed
        case 4:  return .asleepCore
        case 5:  return .asleepDeep
        case 6:  return .asleepREM
        case 7:  return .awake
        default: return nil
        }
    }
}

enum SyncError: LocalizedError {
    case healthKitNotAvailable
    case invalidURL
    case serverError
    case decodingFailed(String)

    var errorDescription: String? {
        switch self {
        case .healthKitNotAvailable:
            return "HealthKit is not available on this device"
        case .invalidURL:
            return "Invalid server URL"
        case .serverError:
            return "Server returned an error. Check Android server is running."
        case .decodingFailed(let message):
            return message
        }
    }
}

private extension SleepSyncManager {
    static func describeDecodingError(_ error: DecodingError, body: String) -> String {
        let prefix: String
        switch error {
        case .keyNotFound(let key, let context):
            prefix = "Missing JSON key '\(key.stringValue)' at \(codingPath(context.codingPath))"
        case .valueNotFound(_, let context):
            prefix = "Missing JSON value at \(codingPath(context.codingPath))"
        case .typeMismatch(_, let context):
            prefix = "JSON type mismatch at \(codingPath(context.codingPath)): \(context.debugDescription)"
        case .dataCorrupted(let context):
            prefix = "JSON data corrupted at \(codingPath(context.codingPath)): \(context.debugDescription)"
        @unknown default:
            prefix = "JSON decode failed"
        }

        return "\(prefix)\nResponse: \(body.prefix(500))"
    }

    static func codingPath(_ path: [CodingKey]) -> String {
        guard !path.isEmpty else { return "$" }
        return "$." + path.map(\.stringValue).joined(separator: ".")
    }
}
