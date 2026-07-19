import Foundation

struct NameIdPair: Codable, Hashable {
    let name: String
    let id: String

    enum CodingKeys: String, CodingKey {
        case name = "Name"
        case id = "Id"
    }
}

struct UserItemData: Codable, Hashable {
    let playbackPositionTicks: Int64
    let playCount: Int
    let isFavorite: Bool
    let played: Bool

    enum CodingKeys: String, CodingKey {
        case playbackPositionTicks = "PlaybackPositionTicks"
        case playCount = "PlayCount"
        case isFavorite = "IsFavorite"
        case played = "Played"
    }
}

struct BaseItem: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let type: String
    let album: String?
    let albumId: String?
    let artists: [String]?
    let artistItems: [NameIdPair]?
    let runTimeTicks: Int64?
    let indexNumber: Int?
    let parentIndexNumber: Int?
    let imageTags: [String: String]?
    let userData: UserItemData?
    let parentId: String?
    let childCount: Int?
    /// Only present when fetched via GET /Playlists/{id}/Items — the entry's position
    /// within that playlist (a string index), needed for remove/reorder calls.
    let playlistItemId: String?

    enum CodingKeys: String, CodingKey {
        case id = "Id"
        case name = "Name"
        case type = "Type"
        case album = "Album"
        case albumId = "AlbumId"
        case artists = "Artists"
        case artistItems = "ArtistItems"
        case runTimeTicks = "RunTimeTicks"
        case indexNumber = "IndexNumber"
        case parentIndexNumber = "ParentIndexNumber"
        case imageTags = "ImageTags"
        case userData = "UserData"
        case parentId = "ParentId"
        case childCount = "ChildCount"
        case playlistItemId = "PlaylistItemId"
    }

    /// Jellyfin ticks are 100ns units: 1ms = 10_000 ticks.
    var runTimeSeconds: TimeInterval? {
        guard let ticks = runTimeTicks else { return nil }
        return TimeInterval(ticks) / 10_000_000
    }

    var hasPrimaryImage: Bool {
        imageTags?["Primary"] != nil
    }

    var artistDisplayName: String {
        artists?.first ?? artistItems?.first?.name ?? ""
    }
}

struct ItemsResult<T: Codable>: Codable {
    let items: [T]
    let totalRecordCount: Int
    /// Absent on some endpoints (e.g. /v1/recommend/*), which don't paginate.
    let startIndex: Int?

    enum CodingKeys: String, CodingKey {
        case items = "Items"
        case totalRecordCount = "TotalRecordCount"
        case startIndex = "StartIndex"
    }
}

extension TimeInterval {
    /// Convert a playback position in seconds to Jellyfin ticks (100ns units).
    var asTicks: Int64 {
        Int64(self * 10_000_000)
    }
}
