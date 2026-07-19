import Foundation

@MainActor
final class ArtistsListViewModel: ObservableObject {
    @Published private(set) var artists: [BaseItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    func loadIfNeeded() async {
        guard artists.isEmpty else { return }
        await load()
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let result = try await apiClient.fetchItems(includeItemTypes: ["MusicArtist"])
            artists = result.items
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
