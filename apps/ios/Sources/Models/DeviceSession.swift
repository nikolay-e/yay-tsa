import Foundation

struct DeviceSession: Codable, Identifiable {
    let sessionId: String
    let deviceId: String
    let userId: String
    let lastSeenAt: String
    let deviceName: String?
    let nowPlayingItemId: String?
    let nowPlayingItemName: String?
    let positionMs: Int64
    let playbackState: String

    var id: String { sessionId }
}
