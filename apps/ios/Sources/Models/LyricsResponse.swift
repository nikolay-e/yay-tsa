import Foundation

struct LyricsResponse: Codable {
    let found: Bool
    let lyrics: String
    let source: String
}
