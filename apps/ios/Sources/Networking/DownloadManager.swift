import Foundation

enum DownloadStatus: Equatable {
    case idle
    case downloading(progress: Double)
    case ready
    case error(String)
}

/// Downloads track audio through the user's own authenticated server for offline
/// playback, storing files under Application Support/Downloads/<trackId>.
@MainActor
final class DownloadManager: ObservableObject {
    @Published private(set) var statuses: [String: DownloadStatus] = [:]

    private let apiClient: APIClient
    private let urlSession: URLSession
    private lazy var downloadsDirectory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    init(apiClient: APIClient, urlSession: URLSession = .shared) {
        self.apiClient = apiClient
        self.urlSession = urlSession
        reindexExistingFiles()
    }

    private func reindexExistingFiles() {
        let files = (try? FileManager.default.contentsOfDirectory(at: downloadsDirectory, includingPropertiesForKeys: nil)) ?? []
        for file in files {
            statuses[file.lastPathComponent] = .ready
        }
    }

    func isDownloaded(_ trackId: String) -> Bool {
        statuses[trackId] == .ready
    }

    func localURL(for trackId: String) -> URL? {
        let url = downloadsDirectory.appendingPathComponent(trackId)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    var downloadedTrackIds: [String] {
        statuses.filter { $0.value == .ready }.map(\.key)
    }

    var totalBytes: Int64 {
        downloadedTrackIds.reduce(0) { total, id in
            let path = downloadsDirectory.appendingPathComponent(id).path
            let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? Int64) ?? 0
            return total + size
        }
    }

    func download(_ item: BaseItem) async {
        guard let remoteURL = apiClient.streamURL(for: item.id) else { return }
        statuses[item.id] = .downloading(progress: 0)
        do {
            let (tempURL, _) = try await urlSession.download(from: remoteURL)
            let destination = downloadsDirectory.appendingPathComponent(item.id)
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: tempURL, to: destination)
            statuses[item.id] = .ready
        } catch {
            statuses[item.id] = .error(error.localizedDescription)
        }
    }

    func delete(_ trackId: String) {
        let url = downloadsDirectory.appendingPathComponent(trackId)
        try? FileManager.default.removeItem(at: url)
        statuses.removeValue(forKey: trackId)
    }

    func deleteAll() {
        for id in downloadedTrackIds {
            delete(id)
        }
    }
}
