import Foundation

struct AdaptiveSession: Codable {
    let id: String
    let userId: String
    let startedAt: String
    let isRadioMode: Bool
    let degraded: String?
}

struct AdaptiveQueueEntry: Codable, Identifiable {
    let id: String
    let trackId: String
    let position: Int
    let addedReason: String?
    let intentLabel: String?
    let status: String
    let queueVersion: Int64
    let addedAt: String
    let playedAt: String?
}

struct AdaptiveQueueResponse: Codable {
    let tracks: [AdaptiveQueueEntry]
}

enum PlaybackSignalType: String {
    case thumbsUp = "THUMBS_UP"
    case thumbsDown = "THUMBS_DOWN"
    case queueJump = "QUEUE_JUMP"
}
