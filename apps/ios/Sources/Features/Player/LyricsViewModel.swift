import Foundation

@MainActor
final class LyricsViewModel: ObservableObject {
    @Published private(set) var response: LyricsResponse?
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient
    private let trackId: String

    init(apiClient: APIClient, trackId: String) {
        self.apiClient = apiClient
        self.trackId = trackId
    }

    func loadIfNeeded() async {
        guard response == nil else { return }
        await load()
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            response = try await apiClient.fetchLyrics(trackId: trackId)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refetch() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            response = try await apiClient.refetchLyrics(trackId: trackId)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
