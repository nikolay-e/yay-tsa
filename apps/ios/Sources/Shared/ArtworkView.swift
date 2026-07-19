import SwiftUI

/// Album/track artwork with a placeholder while loading or when there's no image.
struct ArtworkView: View {
    let url: URL?

    var body: some View {
        ZStack {
            Color.secondary.opacity(0.15)
            if let url {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image.resizable().aspectRatio(contentMode: .fill)
                    } else {
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
    }

    private var placeholder: some View {
        Image(systemName: "music.note")
            .foregroundStyle(.secondary)
            .accessibilityHidden(true)
    }
}
