import SwiftUI

@main
struct YayTsaApp: App {
    @StateObject private var session: SessionStore
    @StateObject private var apiClient: APIClient
    @StateObject private var player = PlayerViewModel()
    @StateObject private var deviceEvents: DeviceEventsClient
    @StateObject private var downloads: DownloadManager
    @StateObject private var radio = RadioViewModel()
    @StateObject private var groupSync = GroupSyncViewModel()

    init() {
        let session = SessionStore()
        _session = StateObject(wrappedValue: session)
        let apiClient = APIClient(session: session)
        _apiClient = StateObject(wrappedValue: apiClient)
        _deviceEvents = StateObject(wrappedValue: DeviceEventsClient(session: session, apiClient: apiClient))
        _downloads = StateObject(wrappedValue: DownloadManager(apiClient: apiClient))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
                .environmentObject(apiClient)
                .environmentObject(player)
                .environmentObject(deviceEvents)
                .environmentObject(downloads)
                .environmentObject(radio)
                .environmentObject(groupSync)
                .onAppear {
                    player.attach(apiClient: apiClient, downloads: downloads)
                    radio.attach(apiClient: apiClient, player: player)
                    groupSync.attach(apiClient: apiClient, session: session, player: player)
                }
                .preferredColorScheme(.dark)
                .tint(Theme.Colors.accent)
        }
    }
}
