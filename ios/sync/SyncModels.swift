import Foundation

struct SyncResponse: Codable {
    let schema: Int
    let generatedAt: Int64
    let sleep: [SleepRecord]

    enum CodingKeys: String, CodingKey {
        case schema
        case generatedAt
        case sleep
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schema = try container.decodeIfPresent(Int.self, forKey: .schema) ?? 1
        generatedAt = try container.decode(Int64.self, forKey: .generatedAt)
        sleep = try container.decodeIfPresent([SleepRecord].self, forKey: .sleep) ?? []
    }
}

struct SleepRecord: Codable {
    let id: String
    let start: Int64
    let end: Int64
    let source: String
    let stages: [SleepStage]

    enum CodingKeys: String, CodingKey {
        case id
        case start
        case end
        case source
        case stages
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        start = try container.decode(Int64.self, forKey: .start)
        end = try container.decode(Int64.self, forKey: .end)
        source = try container.decode(String.self, forKey: .source)
        stages = try container.decodeIfPresent([SleepStage].self, forKey: .stages) ?? []
    }
}

struct SleepStage: Codable {
    let start: Int64
    let end: Int64
    let stage: Int
}
