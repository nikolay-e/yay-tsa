import Foundation

/// Drives an adaptive-DJ "Radio" session: starts/resumes a server-side listening
/// session, resolves its queue (track-id references) into playable items, and
/// forwards user feedback (thumbs up/down, skip) as playback signals.
@MainActor
final class RadioViewModel: ObservableObject {
    @Published private(set) var isActive = false
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private(set) var sessionId: String?
    private var apiClient: APIClient?
    private var player: PlayerViewModel?

    func attach(apiClient: APIClient, player: PlayerViewModel) {
        self.apiClient = apiClient
        self.player = player
    }

    func start(seedTrackId: String? = nil) async {
        guard let apiClient else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let session = try await apiClient.startAdaptiveSession(seedTrackId: seedTrackId)
            sessionId = session.id
            try await loadAndPlayQueue()
            isActive = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stop() async {
        guard let sessionId, let apiClient else { return }
        isActive = false
        self.sessionId = nil
        try? await apiClient.endAdaptiveSession(sessionId: sessionId)
    }

    /// Fetches the queue (id references only) and resolves them to full track
    /// metadata in one batch request before handing the queue to the player.
    /// The server only builds the queue on an explicit refresh call — it is
    /// never populated as a side effect of starting the session.
    private func loadAndPlayQueue() async throws {
        guard let sessionId, let apiClient, let player else { return }
        try await apiClient.refreshAdaptiveQueue(sessionId: sessionId)
        let entries = try await apiClient.fetchAdaptiveQueue(sessionId: sessionId)
        guard !entries.isEmpty else { return }
        let ids = entries.sorted { $0.position < $1.position }.map(\.trackId)
        let resolved = try await apiClient.fetchItems(ids: ids)
        // Preserve queue order — /Items?Ids= doesn't guarantee response order matches the request.
        let byId = Dictionary(uniqueKeysWithValues: resolved.items.map { ($0.id, $0) })
        let tracks = ids.compactMap { byId[$0] }
        guard !tracks.isEmpty else { return }
        player.play(queue: tracks, startAt: 0)
    }

    /// Requests more tracks from the server and appends them once the local
    /// queue is running low — call from near the end of playback.
    func extendQueueIfNeeded() async {
        guard let sessionId, isActive, let apiClient else { return }
        try? await apiClient.refreshAdaptiveQueue(sessionId: sessionId)
    }

    func sendSignal(_ type: PlaybackSignalType, for trackId: String) {
        guard let sessionId, let apiClient else { return }
        Task { try? await apiClient.sendPlaybackSignal(sessionId: sessionId, trackId: trackId, type: type) }
    }
}
