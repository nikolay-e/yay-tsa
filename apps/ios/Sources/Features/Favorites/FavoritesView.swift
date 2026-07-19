import SwiftUI

struct FavoritesView: View {
    @EnvironmentObject private var player: PlayerViewModel
    @StateObject private var viewModel: FavoritesViewModel

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: FavoritesViewModel(apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.tracks.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load favorites",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.tracks.isEmpty {
                ContentUnavailableView("No favorites yet", systemImage: "heart")
            } else {
                List(Array(viewModel.tracks.enumerated()), id: \.element.id) { index, track in
                    HStack {
                        Button {
                            player.play(queue: viewModel.tracks, startAt: index)
                        } label: {
                            Text(track.name).foregroundStyle(Theme.Colors.textPrimary)
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
                .refreshable { await viewModel.load() }
            }
        }
        .background(Theme.Colors.bgPrimary)
        .navigationTitle("Favorites")
        .task { await viewModel.loadIfNeeded() }
        .onReceive(NotificationCenter.default.publisher(for: .yaytsaLibraryDidChange)) { _ in
            Task { await viewModel.load() }
        }
    }
}
