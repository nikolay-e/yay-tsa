import Foundation

struct GroupMember: Codable, Identifiable {
    let deviceId: String
    let userId: String
    let stale: Bool
    let reportedLatencyMs: Int?
    var id: String { deviceId }
}

struct PlaybackSchedule: Codable {
    let groupId: String
    let trackId: String?
    let anchorServerMs: Int64
    let anchorPositionMs: Int64
    let isPaused: Bool
    let scheduleEpoch: Int64
}

enum GroupControlMode: String, Codable {
    case host
    case everyone
}

struct GroupSnapshot: Codable {
    let id: String
    let ownerId: String
    let joinCode: String
    let name: String
    let controlMode: GroupControlMode
    let schedule: PlaybackSchedule
    let members: [GroupMember]
}

struct CreateGroupResponse: Codable {
    let id: String
    let joinCode: String
}

struct ScheduleUpdateResponse: Codable {
    let scheduleEpoch: Int64
    let schedule: PlaybackSchedule
    let serverTimeMs: Int64
}

enum GroupScheduleAction: String {
    case play = "PLAY"
    case pause = "PAUSE"
    case seek = "SEEK"
    case next = "NEXT"
    case prev = "PREV"
    case jump = "JUMP"
}

extension GroupSnapshot {
    func withSchedule(_ schedule: PlaybackSchedule) -> GroupSnapshot {
        GroupSnapshot(id: id, ownerId: ownerId, joinCode: joinCode, name: name, controlMode: controlMode, schedule: schedule, members: members)
    }

    func withControlMode(_ mode: GroupControlMode) -> GroupSnapshot {
        GroupSnapshot(id: id, ownerId: ownerId, joinCode: joinCode, name: name, controlMode: mode, schedule: schedule, members: members)
    }
}
