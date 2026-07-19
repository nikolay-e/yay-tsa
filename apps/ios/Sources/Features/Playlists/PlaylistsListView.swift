import SwiftUI

struct PlaylistsListView: View {
    @EnvironmentObject private var apiClient: APIClient
    @StateObject private var viewModel: PlaylistsListViewModel
    @State private var isShowingNewPlaylist = false
    @State private var newPlaylistName = ""

    init(apiClient: APIClient) {
        _viewModel = StateObject(wrappedValue: PlaylistsListViewModel(apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.playlists.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage {
                ContentUnavailableView(
                    "Couldn't load playlists",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.playlists.isEmpty {
                ContentUnavailableView("No playlists", systemImage: "music.note.list")
            } else {
                List(viewModel.playlists) { playlist in
                    NavigationLink(value: playlist) {
                        HStack {
                            Image(systemName: "music.note.list")
                                .foregroundStyle(Theme.Colors.accent)
                            Text(playlist.name)
                                .foregroundStyle(Theme.Colors.textPrimary)
                        }
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
        .navigationTitle("Playlists")
        .navigationDestination(for: BaseItem.self) { playlist in
            PlaylistDetailView(playlist: playlist, apiClient: apiClient)
        }
        .task { await viewModel.loadIfNeeded() }
        .onReceive(NotificationCenter.default.publisher(for: .yaytsaLibraryDidChange)) { _ in
            Task { await viewModel.load() }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    isShowingNewPlaylist = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add")
            }
        }
        .alert("New Playlist", isPresented: $isShowingNewPlaylist) {
            TextField("Name", text: $newPlaylistName)
            Button("Cancel", role: .cancel) { newPlaylistName = "" }
            Button("Create") {
                let name = newPlaylistName
                newPlaylistName = ""
                Task { await viewModel.createPlaylist(name: name) }
            }
        }
    }
}
