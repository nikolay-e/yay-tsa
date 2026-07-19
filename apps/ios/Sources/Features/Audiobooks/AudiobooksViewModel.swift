import Foundation

@MainActor
final class AudiobooksViewModel: ObservableObject {
    @Published private(set) var books: [AudiobookBook] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    func loadIfNeeded() async {
        guard books.isEmpty else { return }
        await load()
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let entries = try await apiClient.fetchAudiobooks()
            books = AudiobookGrouping.group(entries)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
