import Foundation

struct LoginRequest: Codable {
    let username: String
    let pw: String

    enum CodingKeys: String, CodingKey {
        case username = "Username"
        case pw = "Pw"
    }
}

struct UserPolicy: Codable {
    let isAdministrator: Bool
    let isDisabled: Bool
    let enableAllFolders: Bool

    enum CodingKeys: String, CodingKey {
        case isAdministrator = "IsAdministrator"
        case isDisabled = "IsDisabled"
        case enableAllFolders = "EnableAllFolders"
    }
}

struct MediaServerUser: Codable {
    let id: String
    let name: String
    let serverId: String
    let hasPassword: Bool
    let policy: UserPolicy

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case name = "Name"
        case serverId = "ServerId"
        case hasPassword = "HasPassword"
        case policy = "Policy"
    }
}

struct SessionInfo: Codable {
    let id: String
    let userId: String
    let deviceId: String?
    let deviceName: String?

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case userId = "UserId"
        case deviceId = "DeviceId"
        case deviceName = "DeviceName"
    }
}

struct AuthResponse: Codable {
    let user: MediaServerUser
    let sessionInfo: SessionInfo
    let accessToken: String
    let serverId: String

    enum CodingKeys: String, CodingKey {
        case user = "User"
        case sessionInfo = "SessionInfo"
        case accessToken = "AccessToken"
        case serverId = "ServerId"
    }
}
