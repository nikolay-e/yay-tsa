import SwiftUI

struct ArtistDetailView: View {
    @EnvironmentObject private var player: PlayerViewModel
    let artist: BaseItem
    @StateObject private var viewModel: ArtistDetailViewModel

    init(artist: BaseItem, apiClient: APIClient) {
        self.artist = artist
        _viewModel = StateObject(wrappedValue: ArtistDetailViewModel(artist: artist, apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load artist",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.isEmpty {
                ContentUnavailableView("No albums or tracks", systemImage: "square.stack")
            } else {
                List {
                    if !viewModel.albums.isEmpty {
                        Section("Albums") {
                            ForEach(viewModel.albums) { album in
                                NavigationLink(value: album) {
                                    AlbumGridRow(album: album)
                                }
                                .listRowBackground(Theme.Colors.bgPrimary)
                            }
                        }
                    }
                    if !viewModel.looseTracks.isEmpty {
                        Section("Tracks") {
                            ForEach(Array(viewModel.looseTracks.enumerated()), id: \.element.id) { index, track in
                                HStack {
                                    Button {
                                        player.play(queue: viewModel.looseTracks, startAt: index)
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
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(Theme.Colors.bgPrimary)
            }
        }
        .background(Theme.Colors.bgPrimary)
        .navigationTitle(artist.name)
        .task { await viewModel.loadIfNeeded() }
    }
}

private struct AlbumGridRow: View {
    @EnvironmentObject private var apiClient: APIClient
    let album: BaseItem

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(url: apiClient.imageURL(for: album, maxWidth: 100))
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.sm))

            Text(album.name)
                .foregroundStyle(Theme.Colors.textPrimary)
        }
    }
}
