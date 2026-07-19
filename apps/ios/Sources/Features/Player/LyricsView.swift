import SwiftUI

/// Displays whatever raw lyrics text the server returns (LRC-tagged or plain) for the
/// current track — this view never generates or hardcodes lyrics content itself, it only
/// renders and time-syncs the API response.
struct LyricsView: View {
    @StateObject private var viewModel: LyricsViewModel
    @Environment(\.dismiss) private var dismiss
    let trackName: String

    init(apiClient: APIClient, trackId: String, trackName: String) {
        _viewModel = StateObject(wrappedValue: LyricsViewModel(apiClient: apiClient, trackId: trackId))
        self.trackName = trackName
    }

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.response == nil {
                    ProgressView()
                } else if let errorMessage = viewModel.errorMessage {
                    ContentUnavailableView(
                        "Couldn't load lyrics",
                        systemImage: "exclamationmark.triangle",
                        description: Text(errorMessage)
                    )
                } else if let response = viewModel.response, response.found {
                    LyricsContent(rawLyrics: response.lyrics)
                } else {
                    ContentUnavailableView("No lyrics found", systemImage: "text.quote")
                }
            }
            .background(Theme.Colors.bgPrimary)
            .navigationTitle(trackName)
            .navigationBarTitleDisplayMode(.inline)
            .task { await viewModel.loadIfNeeded() }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await viewModel.refetch() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("Refetch lyrics")
                }
            }
        }
    }
}

private struct LyricsContent: View {
    let parsed: ParsedLyrics

    init(rawLyrics: String) {
        parsed = LrcParser.parse(rawLyrics)
    }

    var body: some View {
        if parsed.isTimeSynced {
            SyncedLyricsScroller(lines: parsed.lines)
        } else {
            VStack(spacing: 0) {
                Text("Lyrics are not time-synced")
                    .font(.caption)
                    .foregroundStyle(Theme.Colors.textTertiary)
                    .padding(.top, 8)
                ScrollView {
                    Text(parsed.lines.map(\.text).joined(separator: "\n"))
                        .font(.body)
                        .foregroundStyle(Theme.Colors.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                }
            }
        }
    }
}

/// Auto-scrolls and highlights the current line based on `player.currentTime`,
/// dimming lines further from "now" — mirrors the PWA's LyricsScroller.tsx.
private struct SyncedLyricsScroller: View {
    @EnvironmentObject private var player: PlayerViewModel
    let lines: [LyricsLine]

    private var activeIndex: Int? {
        LrcParser.activeLineIndex(lines, currentTime: player.currentTime)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Color.clear.frame(height: 160)
                    ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                        Text(line.text.isEmpty ? " " : line.text)
                            .font(index == activeIndex ? .title3.bold() : .body)
                            .foregroundStyle(color(for: index))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(index)
                    }
                    Color.clear.frame(height: 160)
                }
                .padding(.horizontal)
                .animation(.easeOut(duration: 0.3), value: activeIndex)
            }
            .onChange(of: activeIndex) { _, newValue in
                guard let newValue else { return }
                withAnimation(.easeOut(duration: 0.4)) {
                    proxy.scrollTo(newValue, anchor: .center)
                }
            }
        }
    }

    private func color(for index: Int) -> Color {
        guard let activeIndex else { return Theme.Colors.textSecondary }
        if index == activeIndex { return Theme.Colors.accent }
        let distance = abs(index - activeIndex)
        if distance <= 4 { return Theme.Colors.textPrimary.opacity(max(0.3, 0.95 - Double(distance) * 0.15)) }
        return Theme.Colors.textTertiary.opacity(0.3)
    }
}
