import Foundation

@MainActor
final class KaraokeViewModel: ObservableObject {
    @Published private(set) var isFeatureEnabled = false
    @Published private(set) var status: KaraokeStatus?
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient
    private let trackId: String
    private var pollTask: Task<Void, Never>?

    init(apiClient: APIClient, trackId: String) {
        self.apiClient = apiClient
        self.trackId = trackId
    }

    func loadIfNeeded() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            isFeatureEnabled = try await apiClient.fetchKaraokeEnabled()
            guard isFeatureEnabled else { return }
            status = try await apiClient.fetchKaraokeStatus(trackId: trackId)
            if status?.isProcessing == true { startPolling() }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func prepare() async {
        do {
            status = try await apiClient.triggerKaraokeProcess(trackId: trackId)
            if status?.isProcessing == true { startPolling() }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// No SSE client here — a short poll is simpler than another long-lived
    /// connection for what's normally a one-off, seconds-to-minutes wait.
    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                guard !Task.isCancelled else { return }
                guard let latest = try? await apiClient.fetchKaraokeStatus(trackId: trackId) else { continue }
                status = latest
                if !latest.isProcessing { return }
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }
}
