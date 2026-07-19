import SwiftUI

struct AlbumDetailView: View {
    @EnvironmentObject private var player: PlayerViewModel
    let album: BaseItem
    @StateObject private var viewModel: AlbumDetailViewModel

    init(album: BaseItem, apiClient: APIClient) {
        self.album = album
        _viewModel = StateObject(wrappedValue: AlbumDetailViewModel(album: album, apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.tracks.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load tracks",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else {
                List(Array(viewModel.tracks.enumerated()), id: \.element.id) { index, track in
                    HStack {
                        Button {
                            player.play(queue: viewModel.tracks, startAt: index)
                        } label: {
                            TrackRow(track: track)
                        }
                        .buttonStyle(.plain)
                        Spacer()
                        DownloadButton(item: track)
                        FavoriteButton(item: track)
                    }
                    .listRowBackground(Theme.Colors.bgPrimary)
                    .contextMenu {
                        TrackContextMenu(item: track)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(Theme.Colors.bgPrimary)
            }
        }
        .background(Theme.Colors.bgPrimary)
        .navigationTitle(album.name)
        .task { await viewModel.loadIfNeeded() }
    }
}

private struct TrackRow: View {
    let track: BaseItem

    var body: some View {
        HStack {
            Text(track.indexNumber.map(String.init) ?? "-")
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(Theme.Colors.textTertiary)
                .frame(width: 24, alignment: .trailing)

            Text(track.name)
                .foregroundStyle(Theme.Colors.textPrimary)

            Spacer()

            if let seconds = track.runTimeSeconds {
                Text(Self.format(seconds))
                    .font(.caption)
                    .foregroundStyle(Theme.Colors.textSecondary)
            }
        }
    }

    private static func format(_ seconds: TimeInterval) -> String {
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
