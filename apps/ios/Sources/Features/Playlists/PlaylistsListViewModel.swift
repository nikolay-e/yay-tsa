import Foundation

@MainActor
final class PlaylistsListViewModel: ObservableObject {
    @Published private(set) var playlists: [BaseItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?
    @Published var isCreating = false

    private let apiClient: APIClient

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    func loadIfNeeded() async {
        guard playlists.isEmpty else { return }
        await load()
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result = try await apiClient.fetchItems(includeItemTypes: ["Playlist"])
            playlists = result.items
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createPlaylist(name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isCreating = true
        defer { isCreating = false }
        do {
            _ = try await apiClient.createPlaylist(name: trimmed)
            await load()
            Toast.show("Playlist created", kind: .success)
        } catch {
            errorMessage = error.localizedDescription
            Toast.show("Couldn't create playlist", kind: .error)
        }
    }
}
