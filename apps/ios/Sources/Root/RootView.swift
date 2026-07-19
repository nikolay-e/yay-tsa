import SwiftUI

struct RootView: View {
    @EnvironmentObject private var session: SessionStore
    @EnvironmentObject private var apiClient: APIClient
    @EnvironmentObject private var deviceEvents: DeviceEventsClient

    var body: some View {
        if session.isAuthenticated {
            VStack(spacing: 0) {
                TabView {
                    // Only 4 tabs are visible before iOS folds the rest into "More" —
                    // keep the most-used screens here.
                    NavigationStack {
                        HomeView(apiClient: apiClient)
                    }
                    .tabItem { Label("Home", systemImage: "house") }

                    NavigationStack {
                        SearchView(apiClient: apiClient)
                    }
                    .tabItem { Label("Search", systemImage: "magnifyingglass") }

                    AlbumsListView(apiClient: apiClient)
                        .tabItem { Label("Albums", systemImage: "square.stack") }

                    NavigationStack {
                        ArtistsListView(apiClient: apiClient)
                    }
                    .tabItem { Label("Artists", systemImage: "music.mic") }

                    NavigationStack {
                        PlaylistsListView(apiClient: apiClient)
                    }
                    .tabItem { Label("Playlists", systemImage: "music.note.list") }

                    NavigationStack {
                        FavoritesView(apiClient: apiClient)
                    }
                    .tabItem { Label("Favorites", systemImage: "heart") }

                    NavigationStack {
                        AudiobooksListView(apiClient: apiClient)
                    }
                    .tabItem { Label("Audiobooks", systemImage: "book") }

                    NavigationStack {
                        SettingsView(apiClient: apiClient, session: session)
                    }
                    .tabItem { Label("Settings", systemImage: "gearshape") }
                }
                .tint(Theme.Colors.accent)
                MiniPlayerView()
            }
            .toastOverlay()
            .onAppear { deviceEvents.start() }
            .onDisappear { deviceEvents.stop() }
        } else {
            LoginView(session: session)
        }
    }
}
