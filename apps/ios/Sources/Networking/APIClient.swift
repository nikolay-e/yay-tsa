import Foundation

enum APIError: Error, LocalizedError {
    case invalidServerURL
    case notAuthenticated
    case http(Int)
    case decoding(Error)

    var errorDescription: String? {
        switch self {
        case .invalidServerURL: return "Invalid server address."
        case .notAuthenticated: return "Not signed in."
        case .http(let code): return "Server returned an error (\(code))."
        case .decoding: return "Could not read the server's response."
        }
    }
}

/// Talks to the yay-tsa-v2 backend's Jellyfin-flavored REST API.
@MainActor
final class APIClient: ObservableObject {
    private let session: SessionStore
    private let urlSession: URLSession

    private static let decoder: JSONDecoder = JSONDecoder()
    private static let encoder: JSONEncoder = JSONEncoder()

    init(session: SessionStore, urlSession: URLSession = .shared) {
        self.session = session
        self.urlSession = urlSession
    }

    // MARK: - Auth

    /// Not tied to an existing session — used from the login screen before we have a token.
    static func login(serverURL: URL, username: String, password: String) async throws -> AuthResponse {
        let url = serverURL.appendingPathComponent("Users/AuthenticateByName")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(LoginRequest(username: username, pw: password))

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw APIError.http(code)
        }
        do {
            return try decoder.decode(AuthResponse.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    // MARK: - Self-service account

    struct ChangePasswordBody: Encodable {
        let currentPw: String
        let newPw: String
        enum CodingKeys: String, CodingKey {
            case currentPw = "CurrentPw"
            case newPw = "NewPw"
        }
    }

    /// Returns the fresh access token — every prior token (including the caller's) is
    /// revoked server-side, so SessionStore must be updated with this before continuing.
    func changePassword(currentPassword: String, newPassword: String) async throws -> String {
        let data = try await authenticatedPost(
            path: "Users/Password",
            body: ChangePasswordBody(currentPw: currentPassword, newPw: newPassword)
        )
        struct Response: Decodable {
            let accessToken: String
            enum CodingKeys: String, CodingKey { case accessToken = "AccessToken" }
        }
        do {
            return try Self.decoder.decode(Response.self, from: data).accessToken
        } catch {
            throw APIError.decoding(error)
        }
    }

    // MARK: - Admin: user management

    func fetchUsers() async throws -> [AdminUser] {
        let data = try await authenticatedGet(path: "Admin/Users", query: [])
        do {
            return try Self.decoder.decode([AdminUser].self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    struct CreateUserBody: Encodable {
        let username: String
        let displayName: String?
        let isAdmin: Bool
        enum CodingKeys: String, CodingKey {
            case username = "Username"
            case displayName = "DisplayName"
            case isAdmin = "IsAdmin"
        }
    }

    /// Returns the generated one-time initial password to show the admin.
    func createUser(username: String, displayName: String?, isAdmin: Bool) async throws -> String {
        let data = try await authenticatedPost(
            path: "Admin/Users",
            body: CreateUserBody(username: username, displayName: displayName, isAdmin: isAdmin)
        )
        struct Response: Decodable { let initialPassword: String }
        do {
            return try Self.decoder.decode(Response.self, from: data).initialPassword
        } catch {
            throw APIError.decoding(error)
        }
    }

    func deleteUser(userId: String) async throws {
        _ = try await authenticatedDelete(path: "Admin/Users/\(userId)")
    }

    /// Returns the generated one-time new password to show the admin.
    func resetPassword(userId: String) async throws -> String {
        let data = try await authenticatedPost(path: "Admin/Users/\(userId)/ResetPassword", body: Optional<String>.none)
        struct Response: Decodable { let newPassword: String }
        do {
            return try Self.decoder.decode(Response.self, from: data).newPassword
        } catch {
            throw APIError.decoding(error)
        }
    }

    // MARK: - Library

    func fetchItems(
        parentId: String? = nil,
        includeItemTypes: [String] = [],
        artistIds: [String] = [],
        recursive: Bool = true,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        limit: Int = 200,
        searchTerm: String? = nil,
        isFavorite: Bool? = nil,
        ids: [String] = []
    ) async throws -> ItemsResult<BaseItem> {
        var query: [URLQueryItem] = [
            URLQueryItem(name: "Recursive", value: String(recursive)),
            URLQueryItem(name: "SortBy", value: sortBy),
            URLQueryItem(name: "SortOrder", value: sortOrder),
            URLQueryItem(name: "Limit", value: String(limit)),
        ]
        if let parentId { query.append(URLQueryItem(name: "ParentId", value: parentId)) }
        if !includeItemTypes.isEmpty {
            query.append(URLQueryItem(name: "IncludeItemTypes", value: includeItemTypes.joined(separator: ",")))
        }
        if !artistIds.isEmpty {
            query.append(URLQueryItem(name: "ArtistIds", value: artistIds.joined(separator: ",")))
        }
        if let searchTerm, !searchTerm.isEmpty {
            query.append(URLQueryItem(name: "SearchTerm", value: searchTerm))
        }
        if let isFavorite {
            query.append(URLQueryItem(name: "IsFavorite", value: String(isFavorite)))
        }
        if !ids.isEmpty {
            query.append(URLQueryItem(name: "Ids", value: ids.joined(separator: ",")))
        }

        let data = try await authenticatedGet(path: "Items", query: query)
        do {
            return try Self.decoder.decode(ItemsResult<BaseItem>.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    /// Recommendation feed ("daily-mix" or "discover").
    func fetchRecommendations(feed: String, limit: Int = 30) async throws -> ItemsResult<BaseItem> {
        let query = [URLQueryItem(name: "limit", value: String(limit))]
        let data = try await authenticatedGet(path: "v1/recommend/\(feed)", query: query)
        do {
            return try Self.decoder.decode(ItemsResult<BaseItem>.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    /// AI/semantic search over track meaning, not just title/artist substring match.
    /// Degrades gracefully server-side to lexical search if embeddings aren't available
    /// (e.g. a fresh library with no ML worker run yet) — never errors on that account.
    func searchSemantic(query: String, limit: Int = 20) async throws -> [RecommendedTrack] {
        let items = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        let data = try await authenticatedGet(path: "v1/recommend/search", query: items)
        do {
            return try Self.decoder.decode([RecommendedTrack].self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    // MARK: - DJ Preferences

    func fetchUserPreferences() async throws -> UserPreferences {
        guard let userId = session.userId else { throw APIError.notAuthenticated }
        let data = try await authenticatedGet(path: "v1/users/\(userId)/preferences", query: [])
        do {
            return try UserPreferences(wire: Self.decoder.decode(UserPreferencesWire.self, from: data))
        } catch {
            throw APIError.decoding(error)
        }
    }

    func updateUserPreferences(_ preferences: UserPreferences) async throws {
        guard let userId = session.userId else { throw APIError.notAuthenticated }
        _ = try await authenticatedPut(path: "v1/users/\(userId)/preferences", body: preferences.toWire())
    }

    // MARK: - Adaptive DJ / Radio session

    struct StartSessionBody: Encodable {
        let seed_track_id: String?
    }

    @discardableResult
    func startAdaptiveSession(seedTrackId: String? = nil) async throws -> AdaptiveSession {
        let data = try await authenticatedPost(path: "v1/sessions", body: StartSessionBody(seed_track_id: seedTrackId))
        do {
            return try Self.decoder.decode(AdaptiveSession.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    func fetchActiveAdaptiveSession() async throws -> AdaptiveSession? {
        let data = try await authenticatedGet(path: "v1/sessions/active", query: [])
        guard !data.isEmpty else { return nil }
        return try? Self.decoder.decode(AdaptiveSession.self, from: data)
    }

    func endAdaptiveSession(sessionId: String) async throws {
        _ = try await authenticatedDelete(path: "v1/sessions/\(sessionId)")
    }

    func fetchAdaptiveQueue(sessionId: String) async throws -> [AdaptiveQueueEntry] {
        let data = try await authenticatedGet(path: "v1/sessions/\(sessionId)/queue", query: [])
        do {
            return try Self.decoder.decode(AdaptiveQueueResponse.self, from: data).tracks
        } catch {
            throw APIError.decoding(error)
        }
    }

    func refreshAdaptiveQueue(sessionId: String) async throws {
        _ = try await authenticatedPost(path: "v1/sessions/\(sessionId)/queue/refresh", body: Optional<String>.none)
    }

    struct SignalBody: Encodable {
        let track_id: String
        let signal_type: String
    }

    func sendPlaybackSignal(sessionId: String, trackId: String, type: PlaybackSignalType) async throws {
        _ = try await authenticatedPost(
            path: "v1/sessions/\(sessionId)/signals",
            body: SignalBody(track_id: trackId, signal_type: type.rawValue)
        )
    }

    // MARK: - Group Listen

    struct CreateGroupBody: Encodable {
        let name: String
        let trackId: String?
    }

    func createGroup(name: String, trackId: String? = nil) async throws -> CreateGroupResponse {
        let data = try await authenticatedPost(path: "v1/groups", body: CreateGroupBody(name: name, trackId: trackId))
        do {
            return try Self.decoder.decode(CreateGroupResponse.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    struct JoinGroupBody: Encodable {
        let joinCode: String
    }

    func joinGroup(joinCode: String) async throws -> GroupSnapshot {
        let data = try await authenticatedPost(path: "v1/groups/join", body: JoinGroupBody(joinCode: joinCode))
        do {
            return try Self.decoder.decode(GroupSnapshot.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    func fetchGroupSnapshot(groupId: String) async throws -> GroupSnapshot {
        let data = try await authenticatedGet(path: "v1/groups/\(groupId)", query: [])
        do {
            return try Self.decoder.decode(GroupSnapshot.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    struct ScheduleUpdateBody: Encodable {
        let expected_epoch: Int64
        let action: String
        let trackId: String?
        let positionMs: Int64?
        let paused: Bool?
    }

    @discardableResult
    func updateGroupSchedule(
        groupId: String,
        expectedEpoch: Int64,
        action: GroupScheduleAction,
        trackId: String? = nil,
        positionMs: Int64? = nil,
        paused: Bool? = nil
    ) async throws -> ScheduleUpdateResponse {
        let body = ScheduleUpdateBody(
            expected_epoch: expectedEpoch,
            action: action.rawValue,
            trackId: trackId,
            positionMs: positionMs,
            paused: paused
        )
        let data = try await authenticatedPost(path: "v1/groups/\(groupId)/schedule", body: body)
        do {
            return try Self.decoder.decode(ScheduleUpdateResponse.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    struct ControlModeBody: Encodable {
        let controlMode: String
    }

    func setGroupControlMode(groupId: String, mode: GroupControlMode) async throws {
        _ = try await authenticatedPost(path: "v1/groups/\(groupId)/control-mode", body: ControlModeBody(controlMode: mode.rawValue))
    }

    struct GroupHeartbeatBody: Encodable {
        let rttMs: Int?
    }

    func groupHeartbeat(groupId: String, rttMs: Int? = nil) async throws {
        _ = try await authenticatedPost(path: "v1/groups/\(groupId)/heartbeat", body: GroupHeartbeatBody(rttMs: rttMs))
    }

    func leaveGroup(groupId: String, deviceId: String) async throws {
        _ = try await authenticatedDelete(path: "v1/groups/\(groupId)/members/\(deviceId)")
    }

    func endGroup(groupId: String) async throws {
        _ = try await authenticatedDelete(path: "v1/groups/\(groupId)")
    }

    /// Unauthenticated bare epoch-millis text response, used to compute this device's
    /// clock offset from the server for group-listen drift correction.
    func fetchServerTimeMs() async throws -> Int64 {
        guard let serverURL = session.serverURL else { throw APIError.invalidServerURL }
        let (data, response) = try await urlSession.data(from: serverURL.appendingPathComponent("v1/time"))
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw APIError.http((response as? HTTPURLResponse)?.statusCode ?? -1)
        }
        guard let text = String(data: data, encoding: .utf8),
              let ms = Int64(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw APIError.decoding(APIError.invalidServerURL)
        }
        return ms
    }

    /// A playlist's tracks.
    func fetchPlaylistItems(playlistId: String, limit: Int = 500) async throws -> ItemsResult<BaseItem> {
        let query = [
            URLQueryItem(name: "StartIndex", value: "0"),
            URLQueryItem(name: "Limit", value: String(limit)),
        ]
        let data = try await authenticatedGet(path: "Playlists/\(playlistId)/Items", query: query)
        do {
            return try Self.decoder.decode(ItemsResult<BaseItem>.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    // MARK: - Playlists

    struct CreatePlaylistResponse: Codable {
        let id: String
        enum CodingKeys: String, CodingKey { case id = "Id" }
    }

    @discardableResult
    func createPlaylist(name: String, itemIds: [String] = []) async throws -> String {
        struct Body: Encodable {
            let name: String
            let ids: [String]
            enum CodingKeys: String, CodingKey { case name = "Name"; case ids = "Ids" }
        }
        let data = try await authenticatedPost(path: "Playlists", body: Body(name: name, ids: itemIds))
        do {
            return try Self.decoder.decode(CreatePlaylistResponse.self, from: data).id
        } catch {
            throw APIError.decoding(error)
        }
    }

    func addToPlaylist(playlistId: String, itemIds: [String]) async throws {
        let query = [URLQueryItem(name: "Ids", value: itemIds.joined(separator: ","))]
        _ = try await authenticatedPost(path: "Playlists/\(playlistId)/Items", query: query, body: Optional<String>.none)
    }

    /// `entryIds` are playlist-position entry IDs (`BaseItem.playlistItemId`), not track IDs.
    func removeFromPlaylist(playlistId: String, entryIds: [String]) async throws {
        let query = [URLQueryItem(name: "EntryIds", value: entryIds.joined(separator: ","))]
        _ = try await authenticatedDelete(path: "Playlists/\(playlistId)/Items", query: query)
    }

    /// Moves a single entry (by its playlist-position entry ID) to `newIndex`. The server
    /// has no bulk-reorder endpoint, so drag-to-reorder issues one call per move.
    func movePlaylistItem(playlistId: String, entryId: String, newIndex: Int) async throws {
        _ = try await authenticatedPost(
            path: "Playlists/\(playlistId)/Items/\(entryId)/Move/\(newIndex)",
            body: Optional<String>.none
        )
    }

    /// Playlist deletion reuses the generic Items route, not a dedicated /Playlists/{id}.
    func deletePlaylist(playlistId: String) async throws {
        _ = try await authenticatedDelete(path: "Items/\(playlistId)")
    }

    // MARK: - Favorites

    func setFavorite(itemId: String, isFavorite: Bool) async throws {
        guard let userId = session.userId else { throw APIError.notAuthenticated }
        let query = [URLQueryItem(name: "userId", value: userId)]
        if isFavorite {
            _ = try await authenticatedPost(path: "UserFavoriteItems/\(itemId)", query: query, body: Optional<String>.none)
        } else {
            _ = try await authenticatedDelete(path: "UserFavoriteItems/\(itemId)", query: query)
        }
    }

    // MARK: - Devices (remote control / transfer)

    func fetchDevices() async throws -> [DeviceSession] {
        let data = try await authenticatedGet(path: "v1/me/devices", query: [])
        do {
            return try Self.decoder.decode([DeviceSession].self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    struct DeviceCommand: Encodable {
        let command: String
    }

    func sendDeviceCommand(sessionId: String, command: String) async throws {
        _ = try await authenticatedPost(path: "v1/me/devices/\(sessionId)/command", body: DeviceCommand(command: command))
    }

    struct TransferRequest: Encodable {
        let toDeviceId: String
    }

    func transferPlayback(sessionId: String, toDeviceId: String) async throws {
        _ = try await authenticatedPost(
            path: "v1/me/devices/\(sessionId)/transfer",
            body: TransferRequest(toDeviceId: toDeviceId)
        )
    }

    struct HeartbeatRequest: Encodable {
        let deviceId: String?
        let sessionId: String?
    }

    struct HeartbeatResponse: Codable {
        let deviceId: String
        let sessionId: String
    }

    /// Registers this device as an active session so it shows up in other devices'
    /// "My Devices" panel and can receive remote commands / a playback transfer.
    @discardableResult
    func heartbeat(deviceId: String, sessionId: String? = nil) async throws -> HeartbeatResponse {
        let data = try await authenticatedPost(
            path: "v1/me/devices/heartbeat",
            body: HeartbeatRequest(deviceId: deviceId, sessionId: sessionId)
        )
        do {
            return try Self.decoder.decode(HeartbeatResponse.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    // MARK: - Audiobooks

    func fetchAudiobooks() async throws -> [AudiobookEntry] {
        do {
            let data = try await authenticatedGet(path: "v1/me/audiobooks", query: [])
            return try Self.decoder.decode([AudiobookEntry].self, from: data)
        } catch APIError.http(404) {
            // Older/mismatched backend without this route yet — degrade to an empty shelf.
            return []
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.decoding(error)
        }
    }

    @discardableResult
    func markAudiobookFinished(itemId: String) async throws -> Data {
        try await authenticatedPost(path: "v1/me/audiobooks/\(itemId)/finished", body: Optional<String>.none)
    }

    @discardableResult
    func restartAudiobook(itemId: String) async throws -> Data {
        try await authenticatedPost(path: "v1/me/audiobooks/\(itemId)/restart", body: Optional<String>.none)
    }

    // MARK: - Admin / library scan

    struct ScanStatus: Codable {
        let scanning: Bool
        let lastCompletedAt: String?
        let lastTrackCount: Int?
        enum CodingKeys: String, CodingKey {
            case scanning
            case lastCompletedAt
            case lastTrackCount
        }
    }

    func rescanLibrary() async throws {
        _ = try await authenticatedPost(path: "Admin/Library/Rescan", body: Optional<String>.none)
    }

    /// Recomputes loudness-normalization (ReplayGain) data across the library.
    func refreshReplayGain() async throws {
        _ = try await authenticatedPost(path: "Admin/Library/RefreshReplayGain", body: Optional<String>.none)
    }

    /// Clears the server's image/metadata cache.
    func clearServerCache() async throws {
        _ = try await authenticatedDelete(path: "Admin/Cache")
    }

    func fetchScanStatus() async throws -> ScanStatus {
        let data = try await authenticatedGet(path: "Admin/Library/ScanStatus", query: [])
        do {
            return try Self.decoder.decode(ScanStatus.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    // MARK: - Karaoke

    struct KaraokeEnabledResponse: Decodable { let enabled: Bool }

    func fetchKaraokeEnabled() async throws -> Bool {
        let data = try await authenticatedGet(path: "Karaoke/enabled", query: [])
        do {
            return try Self.decoder.decode(KaraokeEnabledResponse.self, from: data).enabled
        } catch {
            throw APIError.decoding(error)
        }
    }

    func fetchKaraokeStatus(trackId: String) async throws -> KaraokeStatus {
        let data = try await authenticatedGet(path: "Karaoke/\(trackId)/status", query: [])
        do {
            return try Self.decoder.decode(KaraokeStatus.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    @discardableResult
    func triggerKaraokeProcess(trackId: String) async throws -> KaraokeStatus {
        let data = try await authenticatedPost(path: "Karaoke/\(trackId)/process", body: Optional<String>.none)
        do {
            return try Self.decoder.decode(KaraokeStatus.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    func karaokeStemURL(trackId: String, stem: KaraokeStreamMode) -> URL? {
        guard stem != .original, let serverURL = session.serverURL, let token = session.accessToken else { return nil }
        let path = stem == .instrumental ? "instrumental" : "vocals"
        var components = URLComponents(
            url: serverURL.appendingPathComponent("Karaoke/\(trackId)/\(path)"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [URLQueryItem(name: "api_key", value: token)]
        return components?.url
    }

    // MARK: - Lyrics

    func fetchLyrics(trackId: String) async throws -> LyricsResponse {
        let data = try await authenticatedGet(path: "Lyrics/\(trackId)", query: [])
        do {
            return try Self.decoder.decode(LyricsResponse.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    /// Forces a re-fetch from the lyrics source rather than returning a cached sidecar result.
    func refetchLyrics(trackId: String) async throws -> LyricsResponse {
        let data = try await authenticatedPost(path: "Lyrics/\(trackId)/fetch", body: Optional<String>.none)
        do {
            return try Self.decoder.decode(LyricsResponse.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    // MARK: - Playback session reporting

    struct PlaybackInfo: Encodable {
        let itemId: String
        let positionTicks: Int64
        let isPaused: Bool
        enum CodingKeys: String, CodingKey {
            case itemId = "ItemId"
            case positionTicks = "PositionTicks"
            case isPaused = "IsPaused"
        }
    }

    func reportPlaybackStart(itemId: String, positionTicks: Int64) async throws {
        _ = try await authenticatedPost(
            path: "Sessions/Playing",
            body: PlaybackInfo(itemId: itemId, positionTicks: positionTicks, isPaused: false)
        )
    }

    func reportPlaybackProgress(itemId: String, positionTicks: Int64, isPaused: Bool) async throws {
        _ = try await authenticatedPost(
            path: "Sessions/Playing/Progress",
            body: PlaybackInfo(itemId: itemId, positionTicks: positionTicks, isPaused: isPaused)
        )
    }

    func reportPlaybackStopped(itemId: String, positionTicks: Int64) async throws {
        _ = try await authenticatedPost(
            path: "Sessions/Playing/Stopped",
            body: PlaybackInfo(itemId: itemId, positionTicks: positionTicks, isPaused: false)
        )
    }

    /// Image URL for a library item. Falls back to nil if the item has no primary image.
    func imageURL(for item: BaseItem, maxWidth: Int = 400) -> URL? {
        guard item.hasPrimaryImage, let serverURL = session.serverURL, let token = session.accessToken else {
            return nil
        }
        var components = URLComponents(
            url: serverURL.appendingPathComponent("Items/\(item.id)/Images/Primary"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [
            URLQueryItem(name: "maxWidth", value: String(maxWidth)),
            URLQueryItem(name: "api_key", value: token),
        ]
        return components?.url
    }

    /// Direct-play stream URL for a track. No transcoding is supported by the backend.
    func streamURL(for itemId: String) -> URL? {
        guard let serverURL = session.serverURL, let token = session.accessToken else { return nil }
        var components = URLComponents(
            url: serverURL.appendingPathComponent("Audio/\(itemId)/stream"),
            resolvingAgainstBaseURL: false
        )
        components?.queryItems = [URLQueryItem(name: "api_key", value: token)]
        return components?.url
    }

    // MARK: - Private helpers

    private func authenticatedGet(path: String, query: [URLQueryItem]) async throws -> Data {
        try await authenticatedRequest(method: "GET", path: path, query: query, body: Optional<String>.none)
    }

    private func authenticatedPost<Body: Encodable>(
        path: String,
        query: [URLQueryItem] = [],
        body: Body
    ) async throws -> Data {
        try await authenticatedRequest(method: "POST", path: path, query: query, body: body)
    }

    private func authenticatedDelete(path: String, query: [URLQueryItem] = []) async throws -> Data {
        try await authenticatedRequest(method: "DELETE", path: path, query: query, body: Optional<String>.none)
    }

    private func authenticatedPut<Body: Encodable>(
        path: String,
        query: [URLQueryItem] = [],
        body: Body
    ) async throws -> Data {
        try await authenticatedRequest(method: "PUT", path: path, query: query, body: body)
    }

    private func authenticatedRequest<Body: Encodable>(
        method: String,
        path: String,
        query: [URLQueryItem],
        body: Body?
    ) async throws -> Data {
        guard let serverURL = session.serverURL, let token = session.accessToken else {
            throw APIError.notAuthenticated
        }
        var components = URLComponents(url: serverURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)
        components?.queryItems = query.isEmpty ? nil : query
        guard let url = components?.url else { throw APIError.invalidServerURL }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try Self.encoder.encode(body)
        }

        let (data, response) = try await urlSession.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw APIError.http(code)
        }
        return data
    }
}
