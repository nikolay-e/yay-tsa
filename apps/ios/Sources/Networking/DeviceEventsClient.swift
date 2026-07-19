import Foundation

extension Notification.Name {
    /// Posted when an SSE event suggests library/collection state changed on another device
    /// (favorite toggled, playlist edited, etc.) so visible lists can invalidate and reload.
    static let yaytsaLibraryDidChange = Notification.Name("yaytsaLibraryDidChange")
}

private struct DeviceEvent: Decodable {
    let type: String?
    let deviceId: String?

    enum CodingKeys: String, CodingKey {
        case type = "Type"
        case deviceId = "DeviceId"
    }
}

/// Connects to the backend's SSE stream (GET /v1/me/devices/events) so this device
/// finds out about state changes made on other devices, without a full remote-control
/// implementation. Reconnects with backoff on stream failure.
@MainActor
final class DeviceEventsClient: ObservableObject {
    @Published private(set) var isConnected = false

    private let session: SessionStore
    private let apiClient: APIClient
    private var streamTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private let urlSession: URLSession
    private static let decoder = JSONDecoder()

    init(session: SessionStore, apiClient: APIClient, urlSession: URLSession = .shared) {
        self.session = session
        self.apiClient = apiClient
        self.urlSession = urlSession
    }

    func start() {
        stop()
        streamTask = Task { await runLoop() }
        heartbeatTask = Task { await heartbeatLoop() }
    }

    /// Registers this device with the backend every 60s so it appears in other
    /// devices' "My Devices" panel and can receive remote commands / a transfer.
    private func heartbeatLoop() async {
        while !Task.isCancelled {
            if session.isAuthenticated {
                _ = try? await apiClient.heartbeat(deviceId: DeviceIdentity.id)
            }
            try? await Task.sleep(nanoseconds: 60_000_000_000)
        }
    }

    func stop() {
        streamTask?.cancel()
        streamTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        isConnected = false
    }

    private func runLoop() async {
        var backoff: UInt64 = 1
        while !Task.isCancelled {
            guard session.isAuthenticated,
                  let serverURL = session.serverURL,
                  let token = session.accessToken else {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                continue
            }

            var request = URLRequest(url: serverURL.appendingPathComponent("v1/me/devices/events"))
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.setValue("text/event-stream", forHTTPHeaderField: "Accept")

            do {
                let (bytes, response) = try await urlSession.bytes(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                    throw URLError(.badServerResponse)
                }
                isConnected = true
                backoff = 1

                var dataBuffer = ""
                for try await line in bytes.lines {
                    if Task.isCancelled { break }
                    if line.hasPrefix("data:") {
                        dataBuffer += line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                    } else if line.isEmpty, !dataBuffer.isEmpty {
                        handle(payload: dataBuffer)
                        dataBuffer = ""
                    }
                }
            } catch {
                // Stream ended or failed — fall through to backoff + retry below.
            }

            isConnected = false
            if Task.isCancelled { break }
            try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
            backoff = min(backoff * 2, 30)
        }
    }

    private func handle(payload: String) {
        guard let data = payload.data(using: .utf8),
              let event = try? Self.decoder.decode(DeviceEvent.self, from: data) else {
            return
        }
        if event.type == "device_state_changed" {
            NotificationCenter.default.post(name: .yaytsaLibraryDidChange, object: nil)
        }
    }
}
