import Foundation

struct AudiobookResume: Codable {
    let positionMs: Int64
    let runTimeMs: Int64
    let status: String
    let progressPercent: Double
    let remainingMs: Int64
    let updatedAt: String
}

struct AudiobookEntry: Codable, Identifiable {
    let item: BaseItem
    let resume: AudiobookResume

    var id: String { item.id }
}

/// Chapters (individual AudiobookEntry tracks) grouped into a single shelf entry by album,
/// mirroring the PWA's "one card per book, not per chapter" behavior.
struct AudiobookBook: Identifiable {
    let albumId: String
    let title: String
    let author: String
    let chapters: [AudiobookEntry]

    var id: String { albumId }

    var totalChapters: Int { chapters.count }
    var finishedChapters: Int { chapters.filter { $0.resume.status == "finished" }.count }

    /// Index of the first not-finished chapter — where "Play"/"Resume" should start.
    var resumeChapterIndex: Int {
        chapters.firstIndex { $0.resume.status != "finished" } ?? 0
    }

    var progressPercent: Double {
        guard !chapters.isEmpty else { return 0 }
        return Double(finishedChapters) / Double(totalChapters) * 100
    }
}

enum AudiobookGrouping {
    /// Groups flat chapter entries into books by album id (falling back to the item's own id
    /// for standalone/ungrouped audiobook files), sorted by (disc, track) within each book.
    static func group(_ entries: [AudiobookEntry]) -> [AudiobookBook] {
        let grouped = Dictionary(grouping: entries) { $0.item.albumId ?? $0.item.id }
        return grouped.map { albumId, chapters in
            let sorted = chapters.sorted {
                ($0.item.parentIndexNumber ?? 0, $0.item.indexNumber ?? 0)
                    < ($1.item.parentIndexNumber ?? 0, $1.item.indexNumber ?? 0)
            }
            let first = sorted[0].item
            return AudiobookBook(
                albumId: albumId,
                title: first.album ?? first.name,
                author: first.artistDisplayName,
                chapters: sorted
            )
        }.sorted { $0.title < $1.title }
    }
}
