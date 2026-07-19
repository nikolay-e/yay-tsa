import Foundation

@MainActor
final class AlbumDetailViewModel: ObservableObject {
    @Published private(set) var tracks: [BaseItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let album: BaseItem
    private let apiClient: APIClient

    init(album: BaseItem, apiClient: APIClient) {
        self.album = album
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
            let result = try await apiClient.fetchItems(parentId: album.id, includeItemTypes: ["Audio"])
            tracks = result.items.sorted {
                ($0.parentIndexNumber ?? 0, $0.indexNumber ?? 0) < ($1.parentIndexNumber ?? 0, $1.indexNumber ?? 0)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
