import Foundation

@MainActor
final class FavoritesViewModel: ObservableObject {
    @Published private(set) var tracks: [BaseItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient

    init(apiClient: APIClient) {
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
            let result = try await apiClient.fetchItems(includeItemTypes: ["Audio"], isFavorite: true)
            tracks = result.items
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
