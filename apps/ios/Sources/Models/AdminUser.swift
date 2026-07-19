import Foundation

struct AdminUser: Codable, Identifiable {
    let id: String
    let username: String
    let displayName: String?
    let isAdmin: Bool
    let isActive: Bool
    let createdAt: String?
    let lastLoginAt: String?

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case username = "Username"
        case displayName = "DisplayName"
        case isAdmin = "IsAdmin"
        case isActive = "IsActive"
        case createdAt = "CreatedAt"
        case lastLoginAt = "LastLoginAt"
    }
}
