import SwiftUI

struct SearchView: View {
    @EnvironmentObject private var apiClient: APIClient
    @EnvironmentObject private var player: PlayerViewModel
    @StateObject private var viewModel: SearchViewModel

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: SearchViewModel(apiClient: apiClient))
    }

    private var artists: [BaseItem] { viewModel.results.filter { $0.type == "MusicArtist" } }
    private var albums: [BaseItem] { viewModel.results.filter { $0.type == "MusicAlbum" } }
    private var tracks: [BaseItem] { viewModel.results.filter { $0.type == "Audio" } }

    var body: some View {
        Group {
            if viewModel.query.trimmingCharacters(in: .whitespaces).isEmpty {
                ContentUnavailableView("Search your library", systemImage: "magnifyingglass")
            } else if viewModel.isLoading && viewModel.results.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Search failed",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.results.isEmpty {
                ContentUnavailableView.search(text: viewModel.query)
            } else {
                List {
                    if !artists.isEmpty {
                        Section("Artists") {
                            ForEach(artists) { artist in
                                NavigationLink(value: artist) {
                                    Text(artist.name).foregroundStyle(Theme.Colors.textPrimary)
                                }
                            }
                        }
                    }
                    if !albums.isEmpty {
                        Section("Albums") {
                            ForEach(albums) { album in
                                NavigationLink(value: album) {
                                    Text(album.name).foregroundStyle(Theme.Colors.textPrimary)
                                }
                            }
                        }
                    }
                    if !tracks.isEmpty {
                        Section("Tracks") {
                            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                                HStack {
                                    Button {
                                        player.play(queue: tracks, startAt: index)
                                    } label: {
                                        Text(track.name).foregroundStyle(Theme.Colors.textPrimary)
                                    }
                                    .buttonStyle(.plain)
                                    Spacer()
                                    DownloadButton(item: track)
                                    FavoriteButton(item: track)
                                }
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
        .navigationTitle("Search")
        .searchable(text: $viewModel.query, placement: .navigationBarDrawer(displayMode: .always))
        .searchScopes($viewModel.mode) {
            ForEach(SearchMode.allCases) { mode in
                Text(mode.rawValue).tag(mode)
            }
        }
        .navigationDestination(for: BaseItem.self) { item in
            destination(for: item)
        }
    }

    @ViewBuilder
    private func destination(for item: BaseItem) -> some View {
        if item.type == "MusicArtist" {
            ArtistDetailView(artist: item, apiClient: apiClient)
        } else {
            AlbumDetailView(album: item, apiClient: apiClient)
        }
    }
}
