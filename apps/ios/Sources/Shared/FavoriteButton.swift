import SwiftUI

/// Reusable heart toggle dropped into track rows across Albums/Artists/Playlists/Search/Favorites.
struct FavoriteButton: View {
    @EnvironmentObject private var apiClient: APIClient
    let item: BaseItem

    @State private var isFavorite: Bool
    @State private var isUpdating = false

    init(item: BaseItem) {
        self.item = item
        _isFavorite = State(initialValue: item.userData?.isFavorite ?? false)
    }

    var body: some View {
        Button {
            toggle()
        } label: {
            Image(systemName: isFavorite ? "heart.fill" : "heart")
                .foregroundStyle(isFavorite ? Theme.Colors.accent : Theme.Colors.textTertiary)
        }
        .buttonStyle(.plain)
        .disabled(isUpdating)
        .accessibilityIdentifier("favoriteButton.\(item.id)")
        .accessibilityLabel("Favorite")
        .accessibilityValue(isFavorite ? "favorited" : "not favorited")
    }

    private func toggle() {
        let newValue = !isFavorite
        isFavorite = newValue
        isUpdating = true
        Task {
            defer { isUpdating = false }
            do {
                try await apiClient.setFavorite(itemId: item.id, isFavorite: newValue)
            } catch {
                isFavorite = !newValue
            }
        }
    }
}
