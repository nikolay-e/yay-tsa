import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var player: PlayerViewModel
    @EnvironmentObject private var radio: RadioViewModel
    @StateObject private var viewModel: HomeViewModel

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: HomeViewModel(apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load recommendations",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.isEmpty {
                ContentUnavailableView(
                    "Nothing to recommend yet",
                    systemImage: "sparkles",
                    description: Text("Play a few tracks and your Daily Mix will appear here.")
                )
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        RadioCard(defaultSeedId: player.currentItem?.id ?? viewModel.dailyMix.first?.id ?? viewModel.discover.first?.id)
                        TrackSection(
                            title: "Daily Mix",
                            systemImage: "sparkles",
                            tracks: viewModel.dailyMix,
                            onPlay: { index in player.play(queue: viewModel.dailyMix, startAt: index) }
                        )
                        TrackSection(
                            title: "Explore New",
                            systemImage: "safari",
                            tracks: viewModel.discover,
                            onPlay: { index in player.play(queue: viewModel.discover, startAt: index) }
                        )
                    }
                    .padding(.vertical)
                }
                .background(Theme.Colors.bgPrimary)
                .refreshable { await viewModel.load() }
            }
        }
        .background(Theme.Colors.bgPrimary)
        .navigationTitle("Home")
        .task { await viewModel.loadIfNeeded() }
        .onReceive(NotificationCenter.default.publisher(for: .yaytsaLibraryDidChange)) { _ in
            Task { await viewModel.load() }
        }
    }
}

private struct RadioCard: View {
    @EnvironmentObject private var radio: RadioViewModel
    let defaultSeedId: String?

    var body: some View {
        Button {
            Task {
                if radio.isActive {
                    await radio.stop()
                } else {
                    await radio.start(seedTrackId: defaultSeedId)
                }
            }
        } label: {
            HStack {
                Image(systemName: radio.isActive ? "dot.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right")
                    .font(.title2)
                    .foregroundStyle(radio.isActive ? Theme.Colors.accent : Theme.Colors.textPrimary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(radio.isActive ? "Radio Playing" : "Start Radio")
                        .font(.headline)
                        .foregroundStyle(Theme.Colors.textPrimary)
                    Text(radio.isActive ? "Adapts to your feedback — tap to stop" : "An endless mix based on your taste")
                        .font(.caption)
                        .foregroundStyle(Theme.Colors.textSecondary)
                }
                Spacer()
                if radio.isLoading {
                    ProgressView()
                }
            }
            .padding()
            .background(Theme.Colors.bgSecondary)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal)
        }
        .buttonStyle(.plain)
        .disabled(radio.isLoading || (!radio.isActive && defaultSeedId == nil))
    }
}

private struct TrackSection: View {
    let title: String
    let systemImage: String
    let tracks: [BaseItem]
    let onPlay: (Int) -> Void

    var body: some View {
        if !tracks.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Label(title, systemImage: systemImage)
                    .font(.headline)
                    .foregroundStyle(Theme.Colors.textPrimary)
                    .padding(.horizontal)

                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    HStack {
                        Button {
                            onPlay(index)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(track.name)
                                    .foregroundStyle(Theme.Colors.textPrimary)
                                Text(track.artistDisplayName)
                                    .font(.caption)
                                    .foregroundStyle(Theme.Colors.textSecondary)
                            }
                        }
                        .buttonStyle(.plain)
                        Spacer()
                        DownloadButton(item: track)
                        FavoriteButton(item: track)
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 6)
                    .contextMenu {
                        TrackContextMenu(item: track)
                    }
                }
            }
        }
    }
}
