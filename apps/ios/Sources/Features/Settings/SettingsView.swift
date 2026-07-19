import SwiftUI

struct SettingsView: View {
    @ObservedObject var session: SessionStore
    @EnvironmentObject private var downloads: DownloadManager
    @StateObject private var viewModel: SettingsViewModel
    private let apiClient: APIClient

    init(apiClient: APIClient, session: SessionStore) {
        self.session = session
        self.apiClient = apiClient
        _viewModel = StateObject(wrappedValue: SettingsViewModel(apiClient: apiClient))
    }

    var body: some View {
        List {
            Section("Server") {
                LabeledContent("Address", value: session.serverURL?.absoluteString ?? "-")
                NavigationLink("Devices") {
                    DevicesView(apiClient: apiClient, thisDeviceId: DeviceIdentity.id)
                }
            }

            Section("Radio") {
                NavigationLink("DJ Preferences") {
                    DjPreferencesView(apiClient: apiClient)
                }
            }

            if session.isAdmin {
                Section("Library") {
                    if let status = viewModel.scanStatus {
                        LabeledContent("Last scan", value: Self.describe(status))
                    }
                    Button {
                        Task { await viewModel.rescan() }
                    } label: {
                        if viewModel.isRescanning {
                            HStack {
                                ProgressView()
                                Text("Scanning…")
                            }
                        } else {
                            Text("Rescan library")
                        }
                    }
                    .disabled(viewModel.isRescanning)

                    Button {
                        Task { await viewModel.refreshReplayGain() }
                    } label: {
                        if viewModel.isRefreshingReplayGain {
                            HStack {
                                ProgressView()
                                Text("Refreshing…")
                            }
                        } else {
                            Text("Refresh ReplayGain")
                        }
                    }
                    .disabled(viewModel.isRefreshingReplayGain)
                }

                Section {
                    Button {
                        Task { await viewModel.clearServerCache() }
                    } label: {
                        if viewModel.isClearingCache {
                            HStack {
                                ProgressView()
                                Text("Clearing…")
                            }
                        } else {
                            Text("Clear Server Cache")
                        }
                    }
                    .disabled(viewModel.isClearingCache)
                } header: {
                    Text("Server Maintenance")
                }
            }

            Section("Offline Downloads") {
                LabeledContent("Downloaded", value: "\(downloads.downloadedTrackIds.count) tracks")
                LabeledContent("Storage used", value: Self.formatBytes(downloads.totalBytes))
                if !downloads.downloadedTrackIds.isEmpty {
                    Button("Remove All Downloads", role: .destructive) {
                        downloads.deleteAll()
                    }
                }
            }

            Section("Account") {
                NavigationLink("Change Password") {
                    ChangePasswordView(apiClient: apiClient, session: session)
                }
                if session.isAdmin {
                    NavigationLink("Users") {
                        UsersAdminView(apiClient: apiClient)
                    }
                }
            }

            Section("About") {
                LabeledContent("Version", value: Self.appVersion)
            }

            Section {
                Button("Sign Out", role: .destructive) {
                    session.signOut()
                }
            }
        }
        .listRowBackground(Theme.Colors.bgSecondary)
        .scrollContentBackground(.hidden)
        .background(Theme.Colors.bgPrimary)
        .navigationTitle("Settings")
        .task {
            guard session.isAdmin else { return }
            await viewModel.loadStatus()
        }
    }

    private static var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "-"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "-"
        return "\(version) (\(build))"
    }

    private static func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private static func describe(_ status: APIClient.ScanStatus) -> String {
        guard let lastCompletedAt = status.lastCompletedAt else { return "Never" }
        let count = status.lastTrackCount.map { " • \($0) tracks" } ?? ""
        return "\(lastCompletedAt)\(count)"
    }
}
