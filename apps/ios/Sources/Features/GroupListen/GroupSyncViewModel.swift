import Foundation

/// Drives a "Group Listen" session: creates/joins a server-side group, keeps this
/// device's clock offset against the server, mirrors the shared playback schedule
/// (play/pause/seek/track) via SSE + periodic drift correction, and pushes local
/// control actions back to the server when this device has authority to control it.
@MainActor
final class GroupSyncViewModel: ObservableObject {
    @Published private(set) var snapshot: GroupSnapshot?
    @Published private(set) var isLoading = false
    @Published private(set) var isConnected = false
    @Published var errorMessage: String?

    private var apiClient: APIClient?
    private var session: SessionStore?
    private var player: PlayerViewModel?
    private let urlSession: URLSession = .shared

    private var streamTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var driftTask: Task<Void, Never>?
    /// serverNowMs ≈ deviceNowMs + clockOffsetMs, sampled once per session via /v1/time.
    private var clockOffsetMs: Int64 = 0
    private var applyingRemoteSchedule = false

    private static let decoder = JSONDecoder()
    private static let hardSeekThresholdMs: Double = 1500

    func attach(apiClient: APIClient, session: SessionStore, player: PlayerViewModel) {
        self.apiClient = apiClient
        self.session = session
        self.player = player
    }

    var isOwner: Bool {
        guard let snapshot, let userId = session?.userId else { return false }
        return snapshot.ownerId == userId
    }

    var canControl: Bool {
        guard let snapshot else { return false }
        return snapshot.controlMode == .everyone || isOwner
    }

    // MARK: - Lifecycle

    func create(name: String) async {
        guard let apiClient else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let seedId = player?.currentItem?.id
            try await syncClock()
            let created = try await apiClient.createGroup(name: name, trackId: seedId)
            let snap = try await apiClient.fetchGroupSnapshot(groupId: created.id)
            snapshot = snap
            startLiveLoops()
            await applySchedule(snap.schedule)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func join(code: String) async {
        guard let apiClient else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await syncClock()
            let snap = try await apiClient.joinGroup(joinCode: code)
            snapshot = snap
            startLiveLoops()
            await applySchedule(snap.schedule)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func leave() async {
        guard let apiClient, let snapshot else { return }
        stopLiveLoops()
        self.snapshot = nil
        try? await apiClient.leaveGroup(groupId: snapshot.id, deviceId: DeviceIdentity.id)
    }

    func end() async {
        guard let apiClient, let snapshot, isOwner else { return }
        stopLiveLoops()
        self.snapshot = nil
        try? await apiClient.endGroup(groupId: snapshot.id)
    }

    func setControlMode(_ mode: GroupControlMode) {
        guard let apiClient, let snapshot, isOwner else { return }
        Task { try? await apiClient.setGroupControlMode(groupId: snapshot.id, mode: mode) }
    }

    // MARK: - Playback control (only takes effect if canControl)

    func pushPlay() { pushSchedule(action: .play, paused: false) }
    func pushPause() { pushSchedule(action: .pause, paused: true) }
    func pushSeek(toSeconds seconds: TimeInterval) { pushSchedule(action: .seek, positionMs: Int64(seconds * 1000)) }
    func pushTrackChange(trackId: String) { pushSchedule(action: .jump, trackId: trackId, positionMs: 0, paused: false) }

    private func pushSchedule(
        action: GroupScheduleAction,
        trackId: String? = nil,
        positionMs: Int64? = nil,
        paused: Bool? = nil
    ) {
        guard canControl, let apiClient, let snapshot else { return }
        let expectedEpoch = snapshot.schedule.scheduleEpoch
        Task {
            do {
                let resolvedPosition = positionMs ?? Int64((player?.currentTime ?? 0) * 1000)
                let response = try await apiClient.updateGroupSchedule(
                    groupId: snapshot.id,
                    expectedEpoch: expectedEpoch,
                    action: action,
                    trackId: trackId,
                    positionMs: resolvedPosition,
                    paused: paused
                )
                self.snapshot = self.snapshot?.withSchedule(response.schedule)
            } catch APIError.http(409) {
                await reconcile()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func reconcile() async {
        guard let apiClient, let snapshot else { return }
        guard let fresh = try? await apiClient.fetchGroupSnapshot(groupId: snapshot.id) else { return }
        self.snapshot = fresh
        await applySchedule(fresh.schedule)
    }

    // MARK: - Clock sync

    private func syncClock() async throws {
        guard let apiClient else { return }
        let localBeforeMs = Date().timeIntervalSince1970 * 1000
        let serverMs = try await apiClient.fetchServerTimeMs()
        let localAfterMs = Date().timeIntervalSince1970 * 1000
        let estimatedLocalAtServerSampleMs = localBeforeMs + (localAfterMs - localBeforeMs) / 2
        clockOffsetMs = serverMs - Int64(estimatedLocalAtServerSampleMs)
    }

    private var serverNowMs: Int64 {
        Int64(Date().timeIntervalSince1970 * 1000) + clockOffsetMs
    }

    // MARK: - Applying the shared schedule to the local player

    /// Full reconciliation: switches track if needed, seeks to the schedule's expected
    /// position, and matches play/pause state. Called on join/create/reconcile/track-change.
    private func applySchedule(_ schedule: PlaybackSchedule) async {
        guard let player, let apiClient else { return }
        applyingRemoteSchedule = true
        defer { applyingRemoteSchedule = false }

        if let trackId = schedule.trackId, player.currentItem?.id != trackId {
            if let resolved = try? await apiClient.fetchItems(ids: [trackId]).items.first {
                player.play(queue: [resolved], startAt: 0)
            }
        }

        let elapsedMs = schedule.isPaused ? 0 : max(0, serverNowMs - schedule.anchorServerMs)
        let expectedSeconds = Double(schedule.anchorPositionMs + elapsedMs) / 1000
        player.seek(to: max(0, expectedSeconds))

        if schedule.isPaused, player.isPlaying {
            player.togglePlayPause()
        } else if !schedule.isPaused, !player.isPlaying {
            player.togglePlayPause()
        }
    }

    /// Lightweight per-tick correction: only hard-seeks if this device has drifted
    /// past the threshold. Assumes the track is already correct (schedule_changed
    /// events own track switches) — this just keeps position/play-state honest.
    private func applyDriftCorrection() {
        guard let schedule = snapshot?.schedule, let player, !applyingRemoteSchedule else { return }
        guard let trackId = schedule.trackId, player.currentItem?.id == trackId else { return }

        if schedule.isPaused != !player.isPlaying {
            player.togglePlayPause()
        }
        guard !schedule.isPaused else { return }

        let elapsedMs = max(0, serverNowMs - schedule.anchorServerMs)
        let expectedMs = Double(schedule.anchorPositionMs + elapsedMs)
        let driftMs = abs(expectedMs - player.currentTime * 1000)
        if driftMs > Self.hardSeekThresholdMs {
            player.seek(to: expectedMs / 1000)
        }
    }

    // MARK: - Background loops

    private func startLiveLoops() {
        stopLiveLoops()
        streamTask = Task { await runEventStream() }
        heartbeatTask = Task { await heartbeatLoop() }
        driftTask = Task { await driftLoop() }
    }

    private func stopLiveLoops() {
        streamTask?.cancel()
        streamTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        driftTask?.cancel()
        driftTask = nil
        isConnected = false
    }

    private func heartbeatLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let apiClient, let groupId = snapshot?.id else { continue }
            try? await apiClient.groupHeartbeat(groupId: groupId)
        }
    }

    private func driftLoop() async {
        while !Task.isCancelled {
            applyDriftCorrection()
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    private func runEventStream() async {
        var backoff: UInt64 = 1
        while !Task.isCancelled {
            guard let session, let groupId = snapshot?.id,
                  let serverURL = session.serverURL, let token = session.accessToken else {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                continue
            }

            var request = URLRequest(url: serverURL.appendingPathComponent("v1/groups/\(groupId)/events"))
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.setValue("text/event-stream", forHTTPHeaderField: "Accept")

            do {
                let (bytes, response) = try await urlSession.bytes(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                    throw URLError(.badServerResponse)
                }
                isConnected = true
                backoff = 1

                var eventName = "message"
                var dataBuffer = ""
                for try await line in bytes.lines {
                    if Task.isCancelled { break }
                    if line.hasPrefix("event:") {
                        eventName = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
                    } else if line.hasPrefix("data:") {
                        dataBuffer += line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                    } else if line.isEmpty {
                        if !dataBuffer.isEmpty || eventName != "message" {
                            await handle(eventName: eventName, payload: dataBuffer)
                        }
                        eventName = "message"
                        dataBuffer = ""
                    }
                }
            } catch {
                // Stream ended or failed — fall through to backoff + retry below.
            }

            isConnected = false
            guard snapshot != nil, !Task.isCancelled else { break }
            // SSE has no replay buffer server-side — a reconnect gap can silently drop
            // events, so always reconcile via a fresh snapshot fetch after a disconnect.
            await reconcile()
            try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
            backoff = min(backoff * 2, 30)
        }
    }

    private struct ControlModePayload: Decodable {
        let controlMode: GroupControlMode
    }

    private func handle(eventName: String, payload: String) async {
        guard let data = payload.data(using: .utf8) else { return }
        switch eventName {
        case "schedule_changed":
            guard let schedule = try? Self.decoder.decode(PlaybackSchedule.self, from: data) else { return }
            snapshot = snapshot?.withSchedule(schedule)
            await applySchedule(schedule)
        case "member_joined", "member_left":
            await reconcile()
        case "control_mode_changed":
            guard let parsed = try? Self.decoder.decode(ControlModePayload.self, from: data) else { return }
            snapshot = snapshot?.withControlMode(parsed.controlMode)
        case "group_ended":
            stopLiveLoops()
            snapshot = nil
        default:
            break
        }
    }
}
