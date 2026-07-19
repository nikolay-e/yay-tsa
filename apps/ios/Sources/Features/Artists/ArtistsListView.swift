import SwiftUI

struct ArtistsListView: View {
    @EnvironmentObject private var apiClient: APIClient
    @StateObject private var viewModel: ArtistsListViewModel

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: ArtistsListViewModel(apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.artists.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load artists",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.artists.isEmpty {
                ContentUnavailableView("No artists", systemImage: "music.mic")
            } else {
                List(viewModel.artists) { artist in
                    NavigationLink(value: artist) {
                        ArtistRow(artist: artist)
                    }
                    .listRowBackground(Theme.Colors.bgPrimary)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(Theme.Colors.bgPrimary)
                .refreshable { await viewModel.load() }
            }
        }
        .background(Theme.Colors.bgPrimary)
        .navigationTitle("Artists")
        .navigationDestination(for: BaseItem.self) { item in
            if item.type == "MusicArtist" {
                ArtistDetailView(artist: item, apiClient: apiClient)
            } else {
                AlbumDetailView(album: item, apiClient: apiClient)
            }
        }
        .task { await viewModel.loadIfNeeded() }
        .onReceive(NotificationCenter.default.publisher(for: .yaytsaLibraryDidChange)) { _ in
            Task { await viewModel.load() }
        }
    }
}

private struct ArtistRow: View {
    @EnvironmentObject private var apiClient: APIClient
    let artist: BaseItem

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(url: apiClient.imageURL(for: artist, maxWidth: 100))
                .frame(width: 48, height: 48)
                .clipShape(Circle())

            Text(artist.name)
                .foregroundStyle(Theme.Colors.textPrimary)
        }
    }
}
