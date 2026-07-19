import Foundation

@MainActor
final class PlaylistDetailViewModel: ObservableObject {
    @Published private(set) var tracks: [BaseItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?
    @Published private(set) var didDeletePlaylist = false

    let playlist: BaseItem
    private let apiClient: APIClient

    init(playlist: BaseItem, apiClient: APIClient) {
        self.playlist = playlist
        self.apiClient = apiClient
    }

    func loadIfNeeded() async {
        guard tracks.isEmpty else { return }
        await load()
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result = try await apiClient.fetchPlaylistItems(playlistId: playlist.id)
            tracks = result.items
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func removeTracks(at offsets: IndexSet) async {
        let entryIds = offsets.compactMap { tracks[$0].playlistItemId }
        guard !entryIds.isEmpty else { return }
        let removed = offsets
        tracks.remove(atOffsets: removed)
        do {
            try await apiClient.removeFromPlaylist(playlistId: playlist.id, entryIds: entryIds)
            Toast.show(entryIds.count == 1 ? "Removed from playlist" : "Removed \(entryIds.count) tracks", kind: .success)
        } catch {
            errorMessage = error.localizedDescription
            Toast.show("Couldn't remove track", kind: .error)
            await load()
        }
    }

    func moveTrack(from source: IndexSet, to destination: Int) async {
        guard let sourceIndex = source.first, let entryId = tracks[sourceIndex].playlistItemId else { return }
        let targetIndex = destination > sourceIndex ? destination - 1 : destination
        tracks.move(fromOffsets: source, toOffset: destination)
        do {
            try await apiClient.movePlaylistItem(playlistId: playlist.id, entryId: entryId, newIndex: targetIndex)
        } catch {
            errorMessage = error.localizedDescription
            await load()
        }
    }

    func deletePlaylist() async {
        do {
            try await apiClient.deletePlaylist(playlistId: playlist.id)
            didDeletePlaylist = true
            NotificationCenter.default.post(name: .yaytsaLibraryDidChange, object: nil)
            Toast.show("Playlist deleted", kind: .success)
        } catch {
            errorMessage = error.localizedDescription
            Toast.show("Couldn't delete playlist", kind: .error)
        }
    }
}
