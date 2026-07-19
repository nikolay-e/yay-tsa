import SwiftUI

/// "My Devices" panel — lists other active sessions for this user and lets you send
/// remote transport commands or pull playback onto this device.
struct DevicesView: View {
    @StateObject private var viewModel: DevicesViewModel
    let thisDeviceId: String

    init(apiClient: APIClient, thisDeviceId: String) {
        _viewModel = StateObject(wrappedValue: DevicesViewModel(apiClient: apiClient))
        self.thisDeviceId = thisDeviceId
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.devices.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load devices",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.devices.isEmpty {
                ContentUnavailableView("No other devices", systemImage: "ipad.and.iphone")
            } else {
                List(viewModel.devices) { session in
                    DeviceRow(session: session, viewModel: viewModel, thisDeviceId: thisDeviceId)
                        .listRowBackground(Theme.Colors.bgPrimary)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(Theme.Colors.bgPrimary)
                .refreshable { await viewModel.load() }
            }
        }
        .background(Theme.Colors.bgPrimary)
        .navigationTitle("Devices")
        .task { await viewModel.load() }
    }
}

private struct DeviceRow: View {
    let session: DeviceSession
    let viewModel: DevicesViewModel
    let thisDeviceId: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(session.deviceName ?? session.deviceId)
                .foregroundStyle(Theme.Colors.textPrimary)
            if let name = session.nowPlayingItemName {
                Text(name)
                    .font(.caption)
                    .foregroundStyle(Theme.Colors.textSecondary)
            } else {
                Text("Idle")
                    .font(.caption)
                    .foregroundStyle(Theme.Colors.textTertiary)
            }

            HStack(spacing: 20) {
                Button {
                    Task { await viewModel.send(command: "skip_previous", to: session) }
                } label: {
                    Image(systemName: "backward.fill")
                }
                .accessibilityLabel("Previous track")
                Button {
                    let command = session.playbackState == "playing" ? "pause" : "play"
                    Task { await viewModel.send(command: command, to: session) }
                } label: {
                    Image(systemName: session.playbackState == "playing" ? "pause.fill" : "play.fill")
                }
                .accessibilityLabel(session.playbackState == "playing" ? "Pause" : "Play")
                Button {
                    Task { await viewModel.send(command: "skip_next", to: session) }
                } label: {
                    Image(systemName: "forward.fill")
                }
                .accessibilityLabel("Next track")
                Spacer()
                Button("Listen Here") {
                    Task { await viewModel.transfer(to: session, fromDeviceId: thisDeviceId) }
                }
                .font(.caption)
            }
            .foregroundStyle(Theme.Colors.accent)
            .padding(.top, 2)
        }
        .padding(.vertical, 4)
    }
}
