import SwiftUI

struct KaraokeView: View {
    @EnvironmentObject private var player: PlayerViewModel
    @StateObject private var viewModel: KaraokeViewModel
    @Environment(\.dismiss) private var dismiss
    let trackId: String

    init(apiClient: APIClient, trackId: String) {
        self.trackId = trackId
        _viewModel = StateObject(wrappedValue: KaraokeViewModel(apiClient: apiClient, trackId: trackId))
    }

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.status == nil {
                    ProgressView()
                } else if !viewModel.isFeatureEnabled {
                    ContentUnavailableView(
                        "Karaoke not available",
                        systemImage: "mic.slash",
                        description: Text("This server doesn't have karaoke processing enabled.")
                    )
                } else if let errorMessage = viewModel.errorMessage {
                    ContentUnavailableView(
                        "Something went wrong",
                        systemImage: "exclamationmark.triangle",
                        description: Text(errorMessage)
                    )
                } else if let status = viewModel.status {
                    statusContent(status)
                }
            }
            .background(Theme.Colors.bgPrimary)
            .navigationTitle("Karaoke")
            .navigationBarTitleDisplayMode(.inline)
            .task { await viewModel.loadIfNeeded() }
            .onDisappear { viewModel.stopPolling() }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }

    @ViewBuilder
    private func statusContent(_ status: KaraokeStatus) -> some View {
        switch true {
        case status.isNotStarted:
            ContentUnavailableView {
                Label("Vocals not separated yet", systemImage: "mic")
            } description: {
                Text("Separate vocals and instrumental for this track.")
            } actions: {
                Button("Prepare Karaoke") { Task { await viewModel.prepare() } }
                    .tint(Theme.Colors.accent)
            }
        case status.isProcessing:
            VStack(spacing: 12) {
                ProgressView()
                Text("Separating vocals…").foregroundStyle(Theme.Colors.textSecondary)
            }
        case status.isFailed:
            ContentUnavailableView(
                "Processing failed",
                systemImage: "exclamationmark.triangle",
                description: Text(status.message ?? "Unknown error")
            )
        case status.isReady:
            VStack(spacing: 24) {
                Image(systemName: "waveform")
                    .font(.system(size: 48))
                    .foregroundStyle(Theme.Colors.accent)
                    .accessibilityHidden(true)
                Picker("Mode", selection: Binding(
                    get: { player.karaokeMode },
                    set: { player.setKaraokeMode($0, trackId: trackId) }
                )) {
                    ForEach(KaraokeStreamMode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)

                VStack(spacing: 12) {
                    Toggle("Blend vocals", isOn: Binding(
                        get: { player.isBlendActive },
                        set: { enabled in
                            if enabled {
                                player.enterVocalBlend(trackId: trackId)
                            } else {
                                player.exitVocalBlend()
                            }
                        }
                    ))
                    .tint(Theme.Colors.accent)

                    HStack(spacing: 12) {
                        Text("Instrumental").font(.caption).foregroundStyle(Theme.Colors.textTertiary)
                        Slider(
                            value: Binding(
                                get: { player.vocalBlendLevel },
                                set: { player.setVocalBlend($0) }
                            ),
                            in: 0...1
                        )
                        .disabled(!player.isBlendActive)
                        Text("Vocals").font(.caption).foregroundStyle(Theme.Colors.textTertiary)
                    }
                }
                .padding(.horizontal)
            }
            .padding(.top, 40)
        default:
            EmptyView()
        }
    }
}
