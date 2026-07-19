import Foundation

struct DjHardRules: Codable {
    var maxArtistConsecutive: Int?
    var noRepeatHours: Int?
}

struct DjStyle: Codable {
    var discoveryPct: Int?
}

struct UserPreferences {
    var hardRules: DjHardRules
    var djStyle: DjStyle
    var redLines: [String]

    static let empty = UserPreferences(hardRules: DjHardRules(), djStyle: DjStyle(), redLines: [])
}

/// Wire shape: hardRules/softPrefs/djStyle are JSON-encoded STRINGS, not nested
/// objects — the server stores each as an opaque blob and only redLines is a real array.
/// Confirmed directly against a running backend (GET/PUT /v1/users/{id}/preferences).
struct UserPreferencesWire: Codable {
    let hardRules: String
    let softPrefs: String
    let djStyle: String
    let redLines: [String]
}

extension UserPreferences {
    init(wire: UserPreferencesWire) {
        let decoder = JSONDecoder()
        hardRules = (try? decoder.decode(DjHardRules.self, from: Data(wire.hardRules.utf8))) ?? DjHardRules()
        djStyle = (try? decoder.decode(DjStyle.self, from: Data(wire.djStyle.utf8))) ?? DjStyle()
        redLines = wire.redLines
    }

    func toWire() -> UserPreferencesWire {
        let encoder = JSONEncoder()
        let hardRulesJSON = (try? encoder.encode(hardRules)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let djStyleJSON = (try? encoder.encode(djStyle)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return UserPreferencesWire(hardRules: hardRulesJSON, softPrefs: "{}", djStyle: djStyleJSON, redLines: redLines)
    }
}
