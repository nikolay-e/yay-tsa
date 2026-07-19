import Foundation

/// Lightweight result from /v1/recommend/search (semantic/AI search) — not a full
/// BaseItem, just enough to identify the track before hydrating via fetchItems(ids:).
struct RecommendedTrack: Codable {
    let trackId: String
    let name: String
    let artistId: String?
    let artistName: String?
    let albumId: String?
    let albumName: String?
    let imageTag: String?
    let source: String?
    let score: Double?
}
