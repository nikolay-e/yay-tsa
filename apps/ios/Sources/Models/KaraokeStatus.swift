import Foundation

struct KaraokeStatus: Codable {
    let state: String
    let message: String?

    var isReady: Bool { state == "READY" }
    var isProcessing: Bool { state == "PROCESSING" }
    var isFailed: Bool { state == "FAILED" }
    var isNotStarted: Bool { state == "NOT_STARTED" }
}

enum KaraokeStreamMode: String, CaseIterable, Identifiable {
    case original = "Original"
    case instrumental = "Instrumental"
    case vocals = "Vocals"

    var id: String { rawValue }
}
