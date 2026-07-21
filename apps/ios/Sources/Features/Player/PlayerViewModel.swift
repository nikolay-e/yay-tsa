import AVFoundation
import Foundation

enum RepeatMode {
    case off, all, one

    mutating func cycle() {
        switch self {
        case .off: self = .all
        case .all: self = .one
        case .one: self = .off
        }
    }
}

/// Owns the single AVPlayer instance and the current play queue.
/// One instance lives for the app's lifetime, injected via the environment.
@MainActor
final class PlayerViewModel: ObservableObject {
    @Published private(set) var currentItem: BaseItem?
    @Published private(set) var isPlaying: Bool = false
    @Published var currentTime: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var queue: [BaseItem] = []
    @Published private(set) var currentIndex: Int = 0
    @Published private(set) var isShuffled: Bool = false
    @Published private(set) var repeatMode: RepeatMode = .off
    @Published var volume: Float = 1.0 {
        didSet {
            player?.volume = volume
            secondaryPlayer?.volume = volume * vocalBlendLevel
        }
    }
    @Published private(set) var sleepTimerRemaining: TimeInterval?
    @Published private(set) var karaokeMode: KaraokeStreamMode = .original
    /// True while both instrumental and vocal stems are playing simultaneously
    /// with independent gain — a continuous alternative to the discrete karaokeMode picker.
    @Published private(set) var isBlendActive = false
    /// 0 = pure instrumental, 1 = pure vocals. Only meaningful while `isBlendActive`.
    @Published var vocalBlendLevel: Float = 0.5 {
        didSet { secondaryPlayer?.volume = volume * vocalBlendLevel }
    }
    @Published private(set) var playbackRate: Float = 1.0
    /// Set by callers that start audiobook playback — gates the speed control's
    /// visibility, since a 1.5x button makes no sense for music tracks.
    @Published private(set) var isAudiobookContext = false

    static let audiobookSpeedSteps: [Float] = [0.75, 1.0, 1.25, 1.5, 1.75, 2.0]

    private var apiClient: APIClient?
    private var downloads: DownloadManager?
    private var player: AVPlayer?
    /// Vocals stream, only present while `isBlendActive` — `player` carries the
    /// instrumental stream in that mode and stays the position/play-state driver.
    private var secondaryPlayer: AVPlayer?
    private var blendDriftTimer: Timer?
    private var timeObserverToken: Any?
    /// The queue in its original (un-shuffled) order, so shuffle can be toggled off cleanly.
    private var unshuffledQueue: [BaseItem] = []
    private var endObserver: NSObjectProtocol?
    private var lastReportedTime: TimeInterval = 0
    private let progressReportInterval: TimeInterval = 10
    private var sleepTimer: Timer?

    func attach(apiClient: APIClient, downloads: DownloadManager? = nil) {
        self.apiClient = apiClient
        self.downloads = downloads
    }

    var hasNext: Bool { currentIndex + 1 < queue.count || repeatMode == .all }
    var hasPrevious: Bool { currentIndex > 0 }

    func play(queue: [BaseItem], startAt index: Int, isAudiobook: Bool = false) {
        self.queue = queue
        unshuffledQueue = queue
        isShuffled = false
        currentIndex = index
        isAudiobookContext = isAudiobook
        playbackRate = 1.0
        loadCurrent(autoplay: true)
    }

    // MARK: - Playback speed (audiobooks only)

    func cyclePlaybackRate() {
        let steps = Self.audiobookSpeedSteps
        let currentStepIndex = steps.firstIndex(of: playbackRate) ?? 1
        playbackRate = steps[(currentStepIndex + 1) % steps.count]
        guard isPlaying else { return }
        player?.rate = playbackRate
    }

    // MARK: - Shuffle / repeat

    func toggleShuffle() {
        guard let current = currentItem else { return }
        if isShuffled {
            queue = unshuffledQueue
            currentIndex = queue.firstIndex(where: { $0.id == current.id }) ?? 0
        } else {
            unshuffledQueue = queue
            var rest = queue
            rest.remove(at: currentIndex)
            rest.shuffle()
            queue = [current] + rest
            currentIndex = 0
        }
        isShuffled.toggle()
    }

    func cycleRepeatMode() {
        repeatMode.cycle()
    }

    // MARK: - Karaoke

    /// Swaps the playing stream between the original mix and a separated stem,
    /// preserving playback position and paused/playing state.
    func setKaraokeMode(_ mode: KaraokeStreamMode, trackId: String) {
        guard mode != karaokeMode, let apiClient else { return }
        if isBlendActive {
            stopBlendDriftWatchdog()
            tearDownSecondaryPlayer()
            isBlendActive = false
        }
        let resumeAt = currentTime
        let wasPlaying = isPlaying

        let url: URL?
        if mode == .original {
            url = downloads?.localURL(for: trackId) ?? apiClient.streamURL(for: trackId)
        } else {
            url = apiClient.karaokeStemURL(trackId: trackId, stem: mode)
        }
        guard let url else { return }

        tearDownPlayer()
        let newPlayer = AVPlayer(url: url)
        newPlayer.volume = volume
        player = newPlayer
        addTimeObserver(to: newPlayer)
        addEndObserver(for: newPlayer)
        karaokeMode = mode

        newPlayer.seek(to: CMTime(seconds: resumeAt, preferredTimescale: 1000)) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, wasPlaying else { return }
                self.player?.play()
                self.isPlaying = true
            }
        }
    }

    /// Plays instrumental (primary, drives position) and vocals (secondary) streams
    /// simultaneously, mixed by `vocalBlendLevel` — a continuous alternative to the
    /// discrete original/instrumental/vocals picker above.
    func enterVocalBlend(trackId: String) {
        guard !isBlendActive, let apiClient else { return }
        guard let instrumentalURL = apiClient.karaokeStemURL(trackId: trackId, stem: .instrumental),
              let vocalsURL = apiClient.karaokeStemURL(trackId: trackId, stem: .vocals) else { return }

        let resumeAt = currentTime
        let wasPlaying = isPlaying
        let seekTime = CMTime(seconds: resumeAt, preferredTimescale: 1000)

        tearDownPlayer()
        tearDownSecondaryPlayer()

        let primary = AVPlayer(url: instrumentalURL)
        primary.volume = volume
        player = primary
        addTimeObserver(to: primary)
        addEndObserver(for: primary)

        let secondary = AVPlayer(url: vocalsURL)
        secondary.volume = volume * vocalBlendLevel
        secondaryPlayer = secondary

        karaokeMode = .instrumental
        isBlendActive = true

        let resumeBothIfNeeded: @Sendable (Bool) -> Void = { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, wasPlaying else { return }
                primary.play()
                secondary.play()
                self.isPlaying = true
            }
        }
        primary.seek(to: seekTime) { _ in
            secondary.seek(to: seekTime, completionHandler: resumeBothIfNeeded)
        }
        startBlendDriftWatchdog()
    }

    func setVocalBlend(_ level: Float) {
        vocalBlendLevel = max(0, min(1, level))
    }

    /// Returns to plain original-stream playback at the current position.
    func exitVocalBlend() {
        guard isBlendActive, let item = currentItem, let apiClient else { return }
        let resumeAt = currentTime
        let wasPlaying = isPlaying

        stopBlendDriftWatchdog()
        tearDownSecondaryPlayer()
        tearDownPlayer()
        isBlendActive = false
        karaokeMode = .original

        guard let url = downloads?.localURL(for: item.id) ?? apiClient.streamURL(for: item.id) else { return }
        let newPlayer = AVPlayer(url: url)
        newPlayer.volume = volume
        player = newPlayer
        addTimeObserver(to: newPlayer)
        addEndObserver(for: newPlayer)

        newPlayer.seek(to: CMTime(seconds: resumeAt, preferredTimescale: 1000)) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, wasPlaying else { return }
                self.player?.play()
                self.isPlaying = true
            }
        }
    }

    private func tearDownSecondaryPlayer() {
        secondaryPlayer?.pause()
        secondaryPlayer = nil
    }

    private func startBlendDriftWatchdog() {
        blendDriftTimer?.invalidate()
        blendDriftTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.correctBlendDrift()
            }
        }
    }

    private func stopBlendDriftWatchdog() {
        blendDriftTimer?.invalidate()
        blendDriftTimer = nil
    }

    /// Hard-snaps the vocals stream back onto the instrumental's position if they've
    /// drifted apart — network buffering hiccups affect each stream independently.
    private func correctBlendDrift() {
        guard isBlendActive, let primary = player, let secondary = secondaryPlayer, primary.rate != 0 else { return }
        let primaryTime = primary.currentTime().seconds
        let secondaryTime = secondary.currentTime().seconds
        guard primaryTime.isFinite, secondaryTime.isFinite else { return }
        if abs(primaryTime - secondaryTime) > 0.3 {
            secondary.seek(to: CMTime(seconds: primaryTime, preferredTimescale: 1000))
        }
    }

    // MARK: - Queue editing

    /// Inserts right after the currently playing item — starts playback if nothing
    /// is queued yet, matching how modern players treat an empty-queue "Play Next".
    func playNext(_ item: BaseItem) {
        guard !queue.isEmpty else {
            play(queue: [item], startAt: 0)
            return
        }
        let insertAt = min(currentIndex + 1, queue.count)
        queue.insert(item, at: insertAt)
        if isShuffled {
            unshuffledQueue.append(item)
        } else {
            unshuffledQueue = queue
        }
    }

    func addToQueue(_ item: BaseItem) {
        guard !queue.isEmpty else {
            play(queue: [item], startAt: 0)
            return
        }
        queue.append(item)
        if isShuffled {
            unshuffledQueue.append(item)
        } else {
            unshuffledQueue = queue
        }
    }

    func moveInQueue(from source: IndexSet, to destination: Int) {
        let current = currentItem
        queue.move(fromOffsets: source, toOffset: destination)
        if !isShuffled { unshuffledQueue = queue }
        if let current, let newIndex = queue.firstIndex(where: { $0.id == current.id }) {
            currentIndex = newIndex
        }
    }

    func removeFromQueue(at offsets: IndexSet) {
        // Never remove the currently playing track through this path.
        let removable = offsets.filter { $0 != currentIndex }
        guard !removable.isEmpty else { return }
        let current = currentItem
        queue.remove(atOffsets: IndexSet(removable))
        if !isShuffled { unshuffledQueue = queue }
        if let current, let newIndex = queue.firstIndex(where: { $0.id == current.id }) {
            currentIndex = newIndex
        }
    }

    func jumpToQueueItem(at index: Int) {
        guard queue.indices.contains(index) else { return }
        currentIndex = index
        loadCurrent(autoplay: true)
    }

    // MARK: - Sleep timer

    func setSleepTimer(minutes: Int) {
        sleepTimer?.invalidate()
        sleepTimerRemaining = TimeInterval(minutes * 60)
        sleepTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, var remaining = self.sleepTimerRemaining else { return }
                remaining -= 1
                if remaining <= 0 {
                    self.cancelSleepTimer()
                    if self.isPlaying { self.togglePlayPause() }
                } else {
                    self.sleepTimerRemaining = remaining
                }
            }
        }
    }

    func cancelSleepTimer() {
        sleepTimer?.invalidate()
        sleepTimer = nil
        sleepTimerRemaining = nil
    }

    func togglePlayPause() {
        guard let player else { return }
        if isPlaying {
            player.pause()
            secondaryPlayer?.pause()
        } else if playbackRate != 1.0 {
            player.rate = playbackRate
            secondaryPlayer?.play()
        } else {
            player.play()
            secondaryPlayer?.play()
        }
        isPlaying.toggle()
        reportProgress(isPaused: !isPlaying)
    }

    func seek(to seconds: TimeInterval) {
        let time = CMTime(seconds: seconds, preferredTimescale: 1000)
        player?.seek(to: time)
        secondaryPlayer?.seek(to: time)
        currentTime = seconds
    }

    func next() {
        guard hasNext else { return }
        if currentIndex + 1 < queue.count {
            currentIndex += 1
        } else {
            currentIndex = 0 // repeat-all wraparound
        }
        loadCurrent(autoplay: true)
    }

    func previous() {
        guard hasPrevious else { return }
        currentIndex -= 1
        loadCurrent(autoplay: true)
    }

    private func loadCurrent(autoplay: Bool) {
        guard queue.indices.contains(currentIndex), let apiClient else { return }
        reportStopped()

        let item = queue[currentIndex]
        currentItem = item
        currentTime = 0
        lastReportedTime = 0
        duration = item.runTimeSeconds ?? 0
        karaokeMode = .original
        if isBlendActive {
            stopBlendDriftWatchdog()
            tearDownSecondaryPlayer()
            isBlendActive = false
        }

        tearDownPlayer()

        // Prefer a downloaded local file (offline playback) over streaming.
        guard let url = downloads?.localURL(for: item.id) ?? apiClient.streamURL(for: item.id) else { return }
        let newPlayer = AVPlayer(url: url)
        newPlayer.volume = volume
        player = newPlayer
        addTimeObserver(to: newPlayer)
        addEndObserver(for: newPlayer)

        if autoplay {
            if playbackRate != 1.0 {
                newPlayer.rate = playbackRate
            } else {
                newPlayer.play()
            }
            isPlaying = true
        }

        Task { try? await apiClient.reportPlaybackStart(itemId: item.id, positionTicks: 0) }
    }

    private func addTimeObserver(to player: AVPlayer) {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.currentTime = time.seconds
                if self.currentTime - self.lastReportedTime >= self.progressReportInterval {
                    self.reportProgress(isPaused: !self.isPlaying)
                }
            }
        }
    }

    private func reportProgress(isPaused: Bool) {
        guard let apiClient, let itemId = currentItem?.id else { return }
        lastReportedTime = currentTime
        let ticks = currentTime.asTicks
        Task { try? await apiClient.reportPlaybackProgress(itemId: itemId, positionTicks: ticks, isPaused: isPaused) }
    }

    private func reportStopped() {
        guard let apiClient, let itemId = currentItem?.id else { return }
        let ticks = currentTime.asTicks
        Task { try? await apiClient.reportPlaybackStopped(itemId: itemId, positionTicks: ticks) }
    }

    private func addEndObserver(for player: AVPlayer) {
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: player.currentItem,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                if self.repeatMode == .one {
                    self.loadCurrent(autoplay: true)
                } else if self.hasNext {
                    self.next()
                } else {
                    self.isPlaying = false
                    self.reportStopped()
                }
            }
        }
    }

    private func tearDownPlayer() {
        if let player, let timeObserverToken {
            player.removeTimeObserver(timeObserverToken)
        }
        timeObserverToken = nil
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = nil
        player?.pause()
        player = nil
    }
}
