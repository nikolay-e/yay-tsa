import Foundation

@MainActor
final class DevicesViewModel: ObservableObject {
    @Published private(set) var devices: [DeviceSession] = []
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let apiClient: APIClient

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            devices = try await apiClient.fetchDevices()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func send(command: String, to session: DeviceSession) async {
        try? await apiClient.sendDeviceCommand(sessionId: session.sessionId, command: command)
    }

    func transfer(to session: DeviceSession, fromDeviceId: String) async {
        try? await apiClient.transferPlayback(sessionId: session.sessionId, toDeviceId: fromDeviceId)
    }
}
