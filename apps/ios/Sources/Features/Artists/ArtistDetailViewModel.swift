import Foundation

@MainActor
final class ArtistDetailViewModel: ObservableObject {
    @Published private(set) var albums: [BaseItem] = []
    /// Tracks credited to this artist that aren't grouped under any album
    /// (missing "Album" tag in the source file).
    @Published private(set) var looseTracks: [BaseItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let artist: BaseItem
    private let apiClient: APIClient

    var isEmpty: Bool { albums.isEmpty && looseTracks.isEmpty }

    init(artist: BaseItem, apiClient: APIClient) {
        self.artist = artist
        self.apiClient = apiClient
    }

    func loadIfNeeded() async {
        guard isEmpty else { return }
        await load()
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            async let albumsResult = apiClient.fetchItems(parentId: artist.id, includeItemTypes: ["MusicAlbum"])
            async let tracksResult = apiClient.fetchItems(parentId: artist.id, includeItemTypes: ["Audio"])
            albums = try await albumsResult.items
            // Only tracks with no album at all belong here — tracks inside an album
            // are already reachable via that album's detail screen.
            looseTracks = try await tracksResult.items.filter { $0.albumId == nil }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
