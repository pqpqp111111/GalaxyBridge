import Foundation
import HealthKit
import Combine

class SleepSyncManager: ObservableObject {
    @Published var status: String = "Ready"
    @Published var isLoading: Bool = false

    private let healthStore = HKHealthStore()
    private var serverURL: String
    private var lastSyncAttempt: Date?
    private static let bridgeSource = "GalaxyBridge"
    private static let sourceMetadataKey = "source"
    private static let originalIdMetadataKey = "originalId"

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
            let result = try await writeSleepToHealthKit(sleepData)
            await MainActor.run {
                status = "Done! \(result.importedNights) new nights, \(result.savedStages) stages synced. Skipped \(result.skippedExistingNights) existing nights."
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

    private func writeSleepToHealthKit(_ response: SyncResponse) async throws -> WriteResult {
        let sleepType = HKObjectType.categoryType(forIdentifier: .sleepAnalysis)!
        var result = WriteResult()

        for sleepRecord in response.sleep {
            let samples = sleepRecord.stages.compactMap { stage -> HKCategorySample? in
                guard stage.end > stage.start else {
                    return nil
                }

                let startDate = Date(timeIntervalSince1970: Double(stage.start) / 1000.0)
                let endDate = Date(timeIntervalSince1970: Double(stage.end) / 1000.0)

                guard let sleepValue = mapStageToHealthKit(stage.stage) else {
                    return nil
                }

                return HKCategorySample(
                    type: sleepType,
                    value: sleepValue.rawValue,
                    start: startDate,
                    end: endDate,
                    metadata: [
                        Self.sourceMetadataKey: Self.bridgeSource,
                        Self.originalIdMetadataKey: sleepRecord.id
                    ]
                )
            }

            guard !samples.isEmpty else {
                continue
            }

            let start = samples.map(\.startDate).min()!
            let end = samples.map(\.endDate).max()!
            let alreadyImported = try await hasExistingGalaxyBridgeSamples(
                sleepType: sleepType,
                originalId: sleepRecord.id,
                start: start,
                end: end
            )

            if alreadyImported {
                result.skippedExistingNights += 1
                continue
            }

            try await save(samples)

            result.importedNights += 1
            result.savedStages += samples.count
        }

        return result
    }

    private func hasExistingGalaxyBridgeSamples(
        sleepType: HKCategoryType,
        originalId: String,
        start: Date,
        end: Date
    ) async throws -> Bool {
        let datePredicate = HKQuery.predicateForSamples(
            withStart: start,
            end: end,
            options: []
        )
        let originalIdPredicate = HKQuery.predicateForObjects(
            withMetadataKey: Self.originalIdMetadataKey,
            allowedValues: [originalId]
        )
        let sourcePredicate = HKQuery.predicateForObjects(
            withMetadataKey: Self.sourceMetadataKey,
            allowedValues: [Self.bridgeSource]
        )
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [
            datePredicate,
            originalIdPredicate,
            sourcePredicate
        ])

        return try await withCheckedThrowingContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: sleepType,
                predicate: predicate,
                limit: 1,
                sortDescriptors: nil
            ) { _, samples, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                continuation.resume(returning: samples?.isEmpty == false)
            }

            healthStore.execute(query)
        }
    }

    private func save(_ samples: [HKSample]) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            healthStore.save(samples) { success, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                if success {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: SyncError.healthKitSaveFailed)
                }
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
    case healthKitSaveFailed

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
        case .healthKitSaveFailed:
            return "HealthKit did not save the sleep samples."
        }
    }
}

private struct WriteResult {
    var importedNights = 0
    var savedStages = 0
    var skippedExistingNights = 0
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
