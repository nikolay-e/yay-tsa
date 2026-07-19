import Foundation

@MainActor
final class SettingsViewModel: ObservableObject {
    @Published private(set) var scanStatus: APIClient.ScanStatus?
    @Published private(set) var isRescanning = false
    @Published private(set) var isRefreshingReplayGain = false
    @Published private(set) var isClearingCache = false
    @Published var errorMessage: String?

    private let apiClient: APIClient

    init(apiClient: APIClient) {
        self.apiClient = apiClient
    }

    func loadStatus() async {
        do {
            scanStatus = try await apiClient.fetchScanStatus()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func rescan() async {
        isRescanning = true
        defer { isRescanning = false }
        do {
            try await apiClient.rescanLibrary()
            try await Task.sleep(nanoseconds: 1_500_000_000)
            scanStatus = try await apiClient.fetchScanStatus()
            NotificationCenter.default.post(name: .yaytsaLibraryDidChange, object: nil)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refreshReplayGain() async {
        isRefreshingReplayGain = true
        defer { isRefreshingReplayGain = false }
        do {
            try await apiClient.refreshReplayGain()
            Toast.show("ReplayGain refresh started", kind: .success)
        } catch {
            errorMessage = error.localizedDescription
            Toast.show("Couldn't start ReplayGain refresh", kind: .error)
        }
    }

    func clearServerCache() async {
        isClearingCache = true
        defer { isClearingCache = false }
        do {
            try await apiClient.clearServerCache()
            Toast.show("Server cache cleared", kind: .success)
        } catch {
            errorMessage = error.localizedDescription
            Toast.show("Couldn't clear server cache", kind: .error)
        }
    }
}
