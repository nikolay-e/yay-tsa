import Foundation

@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var dailyMix: [BaseItem] = []
    @Published private(set) var discover: [BaseItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient

    var isEmpty: Bool { dailyMix.isEmpty && discover.isEmpty }

    init(apiClient: APIClient) {
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
            async let mixResult = apiClient.fetchRecommendations(feed: "daily-mix")
            async let discoverResult = apiClient.fetchRecommendations(feed: "discover")
            dailyMix = try await mixResult.items
            discover = try await discoverResult.items
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
