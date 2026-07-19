import Foundation

@MainActor
final class DjPreferencesViewModel: ObservableObject {
    @Published var maxArtistConsecutive: Int = 2
    @Published var noRepeatHours: Int = 4
    @Published var discoveryPct: Double = 50
    @Published private(set) var redLines: [String] = []
    @Published var newRedLine: String = ""

    @Published private(set) var isLoading = false
    @Published private(set) var isSaving = false
    @Published var errorMessage: String?

    private let apiClient: APIClient
    private var hasLoaded = false

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    func loadIfNeeded() async {
        guard !hasLoaded else { return }
        await load()
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false; hasLoaded = true }
        do {
            let prefs = try await apiClient.fetchUserPreferences()
            maxArtistConsecutive = prefs.hardRules.maxArtistConsecutive ?? 2
            noRepeatHours = prefs.hardRules.noRepeatHours ?? 4
            discoveryPct = Double(prefs.djStyle.discoveryPct ?? 50)
            redLines = prefs.redLines
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func addRedLine() {
        let trimmed = newRedLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !redLines.contains(trimmed) else { return }
        redLines.append(trimmed)
        newRedLine = ""
        Task { await save() }
    }

    func removeRedLines(at offsets: IndexSet) {
        redLines.remove(atOffsets: offsets)
        Task { await save() }
    }

    func save() async {
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }
        let prefs = UserPreferences(
            hardRules: DjHardRules(maxArtistConsecutive: maxArtistConsecutive, noRepeatHours: noRepeatHours),
            djStyle: DjStyle(discoveryPct: Int(discoveryPct)),
            redLines: redLines
        )
        do {
            try await apiClient.updateUserPreferences(prefs)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
