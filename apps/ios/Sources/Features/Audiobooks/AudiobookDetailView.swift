import SwiftUI

struct AudiobookDetailView: View {
    @EnvironmentObject private var player: PlayerViewModel
    @EnvironmentObject private var apiClient: APIClient
    let book: AudiobookBook

    var body: some View {
        List {
            Section {
                Button {
                    let tracks = book.chapters.map(\.item)
                    player.play(queue: tracks, startAt: book.resumeChapterIndex, isAudiobook: true)
                } label: {
                    Label(
                        book.finishedChapters > 0 ? "Resume" : "Play",
                        systemImage: "play.fill"
                    )
                    .foregroundStyle(Theme.Colors.accent)
                }
                .listRowBackground(Theme.Colors.bgPrimary)
            }

            Section("Chapters") {
                ForEach(Array(book.chapters.enumerated()), id: \.element.id) { index, entry in
                    Button {
                        let tracks = book.chapters.map(\.item)
                        player.play(queue: tracks, startAt: index, isAudiobook: true)
                    } label: {
                        ChapterRow(entry: entry)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Theme.Colors.bgPrimary)
                    .swipeActions {
                        if entry.resume.status == "finished" {
                            Button("Restart") {
                                Task { try? await apiClient.restartAudiobook(itemId: entry.item.id) }
                            }
                            .tint(Theme.Colors.accent)
                        } else {
                            Button("Mark Finished") {
                                Task { try? await apiClient.markAudiobookFinished(itemId: entry.item.id) }
                            }
                            .tint(Theme.Colors.success)
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Theme.Colors.bgPrimary)
        .navigationTitle(book.title)
    }
}

private struct ChapterRow: View {
    let entry: AudiobookEntry

    private var statusImage: String {
        switch entry.resume.status {
        case "finished": return "checkmark.circle.fill"
        case "in_progress", "relistening": return "circle.inset.filled"
        default: return "circle"
        }
    }

    private var statusColor: Color {
        entry.resume.status == "finished" ? Theme.Colors.accent : Theme.Colors.textTertiary
    }

    private var statusAccessibilityValue: String {
        switch entry.resume.status {
        case "finished": return "Finished"
        case "in_progress", "relistening": return "In progress"
        default: return "Not started"
        }
    }

    var body: some View {
        HStack {
            Image(systemName: statusImage)
                .foregroundStyle(statusColor)
                .accessibilityHidden(true)
            Text(entry.item.name)
                .foregroundStyle(Theme.Colors.textPrimary)
            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(statusAccessibilityValue)
    }
}
