import SwiftUI

struct MiniPlayerView: View {
    @EnvironmentObject private var player: PlayerViewModel
    @EnvironmentObject private var apiClient: APIClient
    @State private var showNowPlaying = false

    var body: some View {
        if let item = player.currentItem {
            Button {
                showNowPlaying = true
            } label: {
                HStack(spacing: 12) {
                    ArtworkView(url: apiClient.imageURL(for: item, maxWidth: 100))
                        .frame(width: 40, height: 40)
                        .clipShape(RoundedRectangle(cornerRadius: 6))

                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name)
                            .font(.subheadline)
                            .foregroundStyle(Theme.Colors.textPrimary)
                            .lineLimit(1)
                        Text(item.artistDisplayName)
                            .font(.caption)
                            .foregroundStyle(Theme.Colors.textSecondary)
                            .lineLimit(1)
                    }

                    Spacer()

                    Button {
                        player.togglePlayPause()
                    } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.title2)
                            .foregroundStyle(Theme.Colors.accent)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                .background(Theme.Colors.bgSecondary)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("miniPlayerBar")
            .accessibilityLabel("Now playing: \(item.name) by \(item.artistDisplayName)")
            .sheet(isPresented: $showNowPlaying) {
                NowPlayingView()
            }
        }
    }
}

struct NowPlayingView: View {
    @EnvironmentObject private var player: PlayerViewModel
    @EnvironmentObject private var apiClient: APIClient
    @EnvironmentObject private var radio: RadioViewModel
    @EnvironmentObject private var group: GroupSyncViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showQueue = false
    @State private var showSleepTimerMenu = false
    @State private var showLyrics = false
    @State private var showKaraoke = false
    @State private var showGroupListen = false

    private func handleTogglePlayPause() {
        let willPlay = !player.isPlaying
        player.togglePlayPause()
        guard group.snapshot != nil, group.canControl else { return }
        if willPlay { group.pushPlay() } else { group.pushPause() }
    }

    private func handleSeek(to seconds: TimeInterval) {
        player.seek(to: seconds)
        guard group.snapshot != nil, group.canControl else { return }
        group.pushSeek(toSeconds: seconds)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if let item = player.currentItem {
                    ArtworkView(url: apiClient.imageURL(for: item, maxWidth: 600))
                        .aspectRatio(1, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal)

                    VStack(spacing: 4) {
                        Text(item.name)
                            .font(.title3.bold())
                            .foregroundStyle(Theme.Colors.textPrimary)
                            .multilineTextAlignment(.center)
                        Text(item.artistDisplayName)
                            .font(.subheadline)
                            .foregroundStyle(Theme.Colors.textSecondary)
                    }
                    .padding(.horizontal)

                    VStack(spacing: 4) {
                        Slider(
                            value: Binding(
                                get: { player.currentTime },
                                set: { handleSeek(to: $0) }
                            ),
                            in: 0...max(player.duration, 1)
                        )
                        HStack {
                            Text(Self.format(player.currentTime))
                            Spacer()
                            Text(Self.format(player.duration))
                        }
                        .font(.caption)
                        .foregroundStyle(Theme.Colors.textSecondary)
                    }
                    .padding(.horizontal)

                    HStack(spacing: 32) {
                        if player.isAudiobookContext {
                            Button { player.cyclePlaybackRate() } label: {
                                Text(Self.formatRate(player.playbackRate))
                                    .font(.subheadline.monospacedDigit().bold())
                                    .foregroundStyle(player.playbackRate != 1.0 ? Theme.Colors.accent : Theme.Colors.textTertiary)
                            }
                            .accessibilityLabel("Playback speed \(Self.formatRate(player.playbackRate))")
                        } else {
                            Button { player.toggleShuffle() } label: {
                                Image(systemName: "shuffle")
                                    .foregroundStyle(player.isShuffled ? Theme.Colors.accent : Theme.Colors.textTertiary)
                            }
                            .accessibilityLabel("Shuffle")
                            .accessibilityValue(player.isShuffled ? "On" : "Off")
                        }

                        Button { player.previous() } label: {
                            Image(systemName: "backward.fill").font(.title)
                        }
                        .disabled(!player.hasPrevious)
                        .accessibilityLabel("Previous track")

                        Button { handleTogglePlayPause() } label: {
                            Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                                .font(.system(size: 56))
                                .foregroundStyle(Theme.Colors.accent)
                        }
                        .accessibilityLabel(player.isPlaying ? "Pause" : "Play")

                        Button { player.next() } label: {
                            Image(systemName: "forward.fill").font(.title)
                        }
                        .disabled(!player.hasNext)
                        .accessibilityLabel("Next track")

                        Button { player.cycleRepeatMode() } label: {
                            Image(systemName: repeatIcon)
                                .foregroundStyle(player.repeatMode == .off ? Theme.Colors.textTertiary : Theme.Colors.accent)
                        }
                        .accessibilityLabel("Repeat")
                        .accessibilityValue(repeatAccessibilityValue)
                    }

                    HStack(spacing: 8) {
                        Image(systemName: "speaker.fill").font(.caption)
                            .foregroundStyle(Theme.Colors.textTertiary)
                            .accessibilityHidden(true)
                        Slider(value: $player.volume, in: 0...1)
                            .accessibilityLabel("Volume")
                        Image(systemName: "speaker.wave.3.fill").font(.caption)
                            .foregroundStyle(Theme.Colors.textTertiary)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal)

                    HStack(spacing: 32) {
                        Button { showSleepTimerMenu = true } label: {
                            VStack(spacing: 2) {
                                Image(systemName: "moon.zzz")
                                if let remaining = player.sleepTimerRemaining {
                                    Text(Self.format(remaining)).font(.caption2)
                                }
                            }
                            .foregroundStyle(player.sleepTimerRemaining != nil ? Theme.Colors.accent : Theme.Colors.textTertiary)
                        }
                        .accessibilityLabel("Sleep Timer")
                        .accessibilityValue(player.sleepTimerRemaining.map { Self.format($0) } ?? "Off")
                        .confirmationDialog("Sleep Timer", isPresented: $showSleepTimerMenu) {
                            ForEach([5, 10, 15, 30, 45, 60], id: \.self) { minutes in
                                Button("\(minutes) min") { player.setSleepTimer(minutes: minutes) }
                            }
                            if player.sleepTimerRemaining != nil {
                                Button("Cancel Timer", role: .destructive) { player.cancelSleepTimer() }
                            }
                        }

                        Button { showLyrics = true } label: {
                            Image(systemName: "quote.bubble")
                        }
                        .foregroundStyle(Theme.Colors.textTertiary)
                        .accessibilityLabel("Lyrics")
                        .sheet(isPresented: $showLyrics) {
                            LyricsView(apiClient: apiClient, trackId: item.id, trackName: item.name)
                        }

                        Button { showQueue = true } label: {
                            Image(systemName: "list.bullet")
                                .foregroundStyle(Theme.Colors.textTertiary)
                        }
                        .accessibilityLabel("Queue")
                        .sheet(isPresented: $showQueue) {
                            QueueView()
                        }

                        Button { showKaraoke = true } label: {
                            Image(systemName: "mic")
                                .foregroundStyle(player.karaokeMode != .original ? Theme.Colors.accent : Theme.Colors.textTertiary)
                        }
                        .accessibilityLabel("Karaoke")
                        .sheet(isPresented: $showKaraoke) {
                            KaraokeView(apiClient: apiClient, trackId: item.id)
                        }

                        Button { showGroupListen = true } label: {
                            Image(systemName: "person.2.wave.2")
                                .foregroundStyle(group.snapshot != nil ? Theme.Colors.accent : Theme.Colors.textTertiary)
                        }
                        .accessibilityLabel("Group Listen")
                        .sheet(isPresented: $showGroupListen) {
                            GroupListenView()
                        }
                    }

                    if radio.isActive {
                        HStack(spacing: 40) {
                            Button {
                                radio.sendSignal(.thumbsDown, for: item.id)
                                player.next()
                            } label: {
                                Image(systemName: "hand.thumbsdown")
                                    .font(.title2)
                                    .foregroundStyle(Theme.Colors.textTertiary)
                            }
                            .accessibilityLabel("Not interested, skip")
                            Text("Radio")
                                .font(.caption)
                                .foregroundStyle(Theme.Colors.accent)
                            Button {
                                radio.sendSignal(.thumbsUp, for: item.id)
                            } label: {
                                Image(systemName: "hand.thumbsup")
                                    .font(.title2)
                                    .foregroundStyle(Theme.Colors.textTertiary)
                            }
                            .accessibilityLabel("Like this track")
                        }
                        .task { await radio.extendQueueIfNeeded() }
                    }
                }
                Spacer()
            }
            .padding(.top, 32)
            .background(Theme.Colors.bgPrimary)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }

    private var repeatIcon: String {
        switch player.repeatMode {
        case .off, .all: return "repeat"
        case .one: return "repeat.1"
        }
    }

    private var repeatAccessibilityValue: String {
        switch player.repeatMode {
        case .off: return "Off"
        case .all: return "All"
        case .one: return "One"
        }
    }

    private static func format(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds)
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    private static func formatRate(_ rate: Float) -> String {
        rate.truncatingRemainder(dividingBy: 1) == 0
            ? String(format: "%.0fx", rate)
            : String(format: "%.2fx", rate)
    }
}
