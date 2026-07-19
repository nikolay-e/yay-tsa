import SwiftUI

struct PlaylistDetailView: View {
    @EnvironmentObject private var player: PlayerViewModel
    @Environment(\.dismiss) private var dismiss
    let playlist: BaseItem
    @StateObject private var viewModel: PlaylistDetailViewModel
    @State private var isShowingDeleteConfirm = false

    init(playlist: BaseItem, apiClient: APIClient) {
        self.playlist = playlist
        _viewModel = StateObject(wrappedValue: PlaylistDetailViewModel(playlist: playlist, apiClient: apiClient))
    }

    var body: some View {
        Group {
            if viewModel.isLoading && viewModel.tracks.isEmpty {
                ProgressView()
            } else if let errorMessage = viewModel.errorMessage, viewModel.tracks.isEmpty {
                ContentUnavailableView(
                    "Couldn't load tracks",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if viewModel.tracks.isEmpty {
                ContentUnavailableView("No tracks", systemImage: "music.note")
            } else {
                List {
                    ForEach(Array(viewModel.tracks.enumerated()), id: \.element.id) { index, track in
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
                    .onMove { source, destination in
                        Task { await viewModel.moveTrack(from: source, to: destination) }
                    }
                    .onDelete { offsets in
                        Task { await viewModel.removeTracks(at: offsets) }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(Theme.Colors.bgPrimary)
            }
        }
        .background(Theme.Colors.bgPrimary)
        .navigationTitle(playlist.name)
        .task { await viewModel.loadIfNeeded() }
        .onChange(of: viewModel.didDeletePlaylist) { _, didDelete in
            if didDelete { dismiss() }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        player.play(queue: viewModel.tracks, startAt: 0)
                    } label: {
                        Label("Play All", systemImage: "play.fill")
                    }
                    Button {
                        player.play(queue: viewModel.tracks.shuffled(), startAt: 0)
                    } label: {
                        Label("Shuffle All", systemImage: "shuffle")
                    }
                    Button(role: .destructive) {
                        isShowingDeleteConfirm = true
                    } label: {
                        Label("Delete Playlist", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .disabled(viewModel.tracks.isEmpty && viewModel.isLoading)

                EditButton()
            }
        }
        .confirmationDialog(
            "Delete “\(playlist.name)”?",
            isPresented: $isShowingDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("Delete Playlist", role: .destructive) {
                Task { await viewModel.deletePlaylist() }
            }
        }
    }
}
