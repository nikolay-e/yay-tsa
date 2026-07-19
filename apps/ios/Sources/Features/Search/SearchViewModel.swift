import Foundation

enum SearchMode: String, CaseIterable, Identifiable {
    case text = "Text"
    case semantic = "Semantic"
    var id: String { rawValue }
}

@MainActor
final class SearchViewModel: ObservableObject {
    @Published var query: String = "" {
        didSet { scheduleSearch() }
    }
    @Published var mode: SearchMode = .text {
        didSet { scheduleSearch() }
    }
    @Published private(set) var results: [BaseItem] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient
    private var searchTask: Task<Void, Never>?

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else {
            results = []
            errorMessage = nil
            return
        }
        let mode = self.mode
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            await search(term: term, mode: mode)
        }
    }

    private func search(term: String, mode: SearchMode) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            switch mode {
            case .text:
                let result = try await apiClient.fetchItems(
                    includeItemTypes: ["MusicArtist", "MusicAlbum", "Audio"],
                    searchTerm: term
                )
                guard !Task.isCancelled else { return }
                results = result.items
            case .semantic:
                let hits = try await apiClient.searchSemantic(query: term)
                guard !Task.isCancelled else { return }
                guard !hits.isEmpty else { results = []; return }
                let resolved = try await apiClient.fetchItems(ids: hits.map(\.trackId))
                guard !Task.isCancelled else { return }
                let byId = Dictionary(uniqueKeysWithValues: resolved.items.map { ($0.id, $0) })
                results = hits.compactMap { byId[$0.trackId] }
            }
        } catch {
            guard !Task.isCancelled else { return }
            errorMessage = error.localizedDescription
        }
    }
}
