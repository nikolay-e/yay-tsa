import SwiftUI

struct AlbumsListView: View {
    @EnvironmentObject private var apiClient: APIClient
    @StateObject private var viewModel: AlbumsViewModel

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: AlbumsViewModel(apiClient: apiClient))
    }

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.albums.isEmpty {
                    ProgressView()
                } else if let errorMessage = viewModel.errorMessage {
                    ContentUnavailableView(
                        "Couldn't load albums",
                        systemImage: "exclamationmark.triangle",
                        description: Text(errorMessage)
                    )
                } else if viewModel.albums.isEmpty {
                    ContentUnavailableView("No albums", systemImage: "music.note.list")
                } else {
                    List(viewModel.albums) { album in
                        NavigationLink(value: album) {
                            AlbumRow(album: album)
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
            .navigationTitle("Albums")
            .navigationDestination(for: BaseItem.self) { album in
                AlbumDetailView(album: album, apiClient: apiClient)
            }
        }
        .task { await viewModel.loadIfNeeded() }
        .onReceive(NotificationCenter.default.publisher(for: .yaytsaLibraryDidChange)) { _ in
            Task { await viewModel.load() }
        }
    }
}

private struct AlbumRow: View {
    @EnvironmentObject private var apiClient: APIClient
    let album: BaseItem

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(url: apiClient.imageURL(for: album, maxWidth: 100))
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.sm))

            VStack(alignment: .leading, spacing: 2) {
                Text(album.name)
                    .foregroundStyle(Theme.Colors.textPrimary)
                Text(album.artistDisplayName)
                    .font(.caption)
                    .foregroundStyle(Theme.Colors.textSecondary)
            }
        }
    }
}
