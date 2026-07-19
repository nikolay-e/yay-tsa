import SwiftUI

struct QueueView: View {
    @EnvironmentObject private var player: PlayerViewModel
    @EnvironmentObject private var apiClient: APIClient
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(player.queue.enumerated()), id: \.element.id) { index, item in
                    Button {
                        player.jumpToQueueItem(at: index)
                    } label: {
                        HStack(spacing: 12) {
                            ArtworkView(url: apiClient.imageURL(for: item, maxWidth: 80))
                                .frame(width: 36, height: 36)
                                .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.sm))

                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name)
                                    .foregroundStyle(
                                        index == player.currentIndex ? Theme.Colors.accent : Theme.Colors.textPrimary
                                    )
                                Text(item.artistDisplayName)
                                    .font(.caption)
                                    .foregroundStyle(Theme.Colors.textSecondary)
                            }

                            Spacer()

                            if index == player.currentIndex {
                                Image(systemName: "speaker.wave.2.fill")
                                    .foregroundStyle(Theme.Colors.accent)
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Theme.Colors.bgPrimary)
                    .accessibilityElement(children: .combine)
                    .accessibilityValue(index == player.currentIndex ? "Now playing" : "")
                }
                .onMove { player.moveInQueue(from: $0, to: $1) }
                .onDelete { player.removeFromQueue(at: $0) }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.Colors.bgPrimary)
            .navigationTitle("Queue")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    EditButton()
                }
            }
        }
    }
}
