import SwiftUI

struct DownloadButton: View {
    @EnvironmentObject private var downloads: DownloadManager
    let item: BaseItem

    var body: some View {
        Button {
            Task {
                if downloads.isDownloaded(item.id) {
                    downloads.delete(item.id)
                } else {
                    await downloads.download(item)
                }
            }
        } label: {
            switch downloads.statuses[item.id] {
            case .ready:
                Image(systemName: "arrow.down.circle.fill").foregroundStyle(Theme.Colors.accent)
            case .downloading:
                ProgressView().controlSize(.mini)
            case .error:
                Image(systemName: "exclamationmark.circle").foregroundStyle(Theme.Colors.error)
            case .idle, .none:
                Image(systemName: "arrow.down.circle").foregroundStyle(Theme.Colors.textTertiary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("downloadButton.\(item.id)")
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        switch downloads.statuses[item.id] {
        case .ready: return "Downloaded, tap to remove"
        case .downloading: return "Downloading"
        case .error: return "Download failed, tap to retry"
        case .idle, .none: return "Download for offline listening"
        }
    }
}
