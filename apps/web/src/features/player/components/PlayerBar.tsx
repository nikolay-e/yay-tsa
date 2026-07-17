import { lazy, Suspense, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { type AudioItem, getIsFavorite } from '@yay-tsa/core';
import {
  Mic,
  Timer,
  AlignLeft,
  ThumbsUp,
  ThumbsDown,
  Radio,
  MonitorSmartphone,
  Users,
  ListMusic,
} from 'lucide-react';
import { FavoriteButton } from '@/features/library/components/FavoriteButton';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { getTrackImageUrl } from '@/shared/utils/track-image';
import { cn } from '@/shared/utils/cn';
import { currentTimeOfDay } from '@/shared/utils/time';
import { toast } from '@/shared/ui/Toast';
import { PLAYER_TEST_IDS } from '@/shared/testing/test-ids';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { MarqueeText } from '@/shared/ui/MarqueeText';
import { useIsOnline } from '@/features/offline/stores/offline.store';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
  useVolume,
  useIsShuffle,
  useRepeatMode,
  usePlayerError,
  useIsKaraokeMode,
  useIsKaraokeTransitioning,
  useIsLoading,
  useKaraokeEnabled,
  useKaraokeStatus,
  useVocalBlend,
  useSleepTimer,
} from '../stores/player.store';
import { UnsupportedFormatError } from '../stores/player-errors';
import {
  useActiveSession,
  useSessionActions,
  useIsSessionStarting,
  useSessionStore,
} from '../stores/session-store';
import { useAlbumColors } from '../hooks/useAlbumColors';
import { useSignalEmitter } from '../hooks/useSignalEmitter';
import { usePlaybackHotkeys } from '../hooks/usePlaybackHotkeys';
import { useGroupSyncStore } from '../stores/group-sync-store';
import { useTimingStore } from '../stores/playback-timing.store';
import { nextAudiobookSpeed } from '../playback-speed';
import { MobileFullPlayer } from './MobileFullPlayer';
import { KaraokeBlendSlider } from './KaraokeBlendSlider';
import { SeekBar, TimeDisplay } from './SeekBar';
import { LyricsView } from './LyricsView';
import { SleepTimerModal } from './SleepTimerModal';
import { VolumeControls } from './VolumeControls';
import { PlaybackControls, PlayPauseIcon } from './PlaybackControls';
import { DevicesPanel } from './DevicesPanel';
import { GroupSyncPanel } from './GroupSyncPanel';

const QueuePanel = lazy(() => import('./QueuePanel'));

function MiniBarProgress() {
  const fillRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const apply = (currentTime: number, duration: number) => {
      if (!fillRef.current) return;
      const progress = duration > 0 ? (currentTime / duration) * 100 : 0;
      fillRef.current.style.width = `${progress}%`;
    };

    const { currentTime, duration } = useTimingStore.getState();
    apply(currentTime, duration);

    return useTimingStore.subscribe(state => apply(state.currentTime, state.duration));
  }, []);

  return (
    <div
      aria-hidden
      className="bg-bg-tertiary pointer-events-none absolute top-0 left-0 h-0.5 w-full md:hidden"
    >
      <div ref={fillRef} className="bg-accent h-full" style={{ width: '0%' }} />
    </div>
  );
}

function TrackInfo({
  track,
  hasImageError,
  onImageError,
  imageUrl,
  isRadioMode,
}: Readonly<{
  track: AudioItem;
  hasImageError: boolean;
  onImageError: () => void;
  imageUrl: string;
  isRadioMode: boolean;
}>) {
  const artistName = track.Artists?.[0] ?? 'Unknown Artist';
  const artistId = track.ArtistItems?.[0]?.Id;
  const imgSrc = hasImageError ? getImagePlaceholder() : imageUrl;

  return (
    <div className="flex min-w-0 flex-1 items-center gap-2">
      {track.AlbumId ? (
        <Link to={`/albums/${track.AlbumId}`}>
          <img
            src={imgSrc}
            alt={track.Name}
            className="h-12 w-12 shrink-0 rounded-sm object-cover transition-opacity hover:opacity-80"
            onError={onImageError}
          />
        </Link>
      ) : (
        <img
          src={imgSrc}
          alt={track.Name}
          className="h-12 w-12 shrink-0 rounded-sm object-cover"
          onError={onImageError}
        />
      )}
      <div className="min-w-0">
        <div className="flex items-center gap-1.5">
          {track.AlbumId ? (
            <Link
              to={`/albums/${track.AlbumId}`}
              data-testid="current-track-title"
              className="text-text-primary truncate font-medium hover:underline"
            >
              {track.Name}
            </Link>
          ) : (
            <p data-testid="current-track-title" className="text-text-primary truncate font-medium">
              {track.Name}
            </p>
          )}
          {isRadioMode && (
            <span className="bg-accent/20 text-accent inline-flex shrink-0 items-center gap-0.5 rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wide uppercase">
              <Radio className="h-2.5 w-2.5" />
              Radio
            </span>
          )}
        </div>
        {artistId ? (
          <Link
            to={`/artists/${artistId}`}
            data-testid="current-track-artist"
            className="text-text-secondary hover:text-text-primary block truncate text-sm hover:underline"
          >
            {artistName}
          </Link>
        ) : (
          <p data-testid="current-track-artist" className="text-text-secondary truncate text-sm">
            {artistName}
          </p>
        )}
      </div>
      <FavoriteButton itemId={track.Id} itemType="track" isFavorite={getIsFavorite(track)} />
    </div>
  );
}

export function PlayerBar() {
  const [showSleepModal, setShowSleepModal] = useState(false);
  const [showLyricsView, setShowLyricsView] = useState(false);
  const [showFullPlayer, setShowFullPlayer] = useState(false);
  const [showDevices, setShowDevices] = useState(false);
  const [showGroupSync, setShowGroupSync] = useState(false);
  const [showQueue, setShowQueue] = useState(false);
  const groupMode = useGroupSyncStore(s => s.mode);
  const [sleepMinutesLeft, setSleepMinutesLeft] = useState(0);
  const activeSession = useActiveSession();
  const { sendSignal, startSession } = useSessionActions();
  const isSessionStarting = useIsSessionStarting();
  const isOnline = useIsOnline();
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const volume = useVolume();
  const isShuffle = useIsShuffle();
  const repeatMode = useRepeatMode();
  const playerError = usePlayerError();
  const isLoading = useIsLoading();
  const isKaraokeMode = useIsKaraokeMode();
  const karaokeEnabled = useKaraokeEnabled();
  const isKaraokeTransitioning = useIsKaraokeTransitioning();
  const karaokeStatus = useKaraokeStatus();
  const vocalBlend = useVocalBlend();
  const sleepTimer = useSleepTimer();
  useAlbumColors();
  useSignalEmitter();
  usePlaybackHotkeys();
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    currentTrack?.Id ?? '',
    currentTrack?.AlbumPrimaryImageTag,
    currentTrack?.AlbumId
  );

  const { getImageUrl } = useImageUrl();

  const seek = usePlayerStore(s => s.seek);
  const skipBy = usePlayerStore(s => s.skipBy);
  const playerMode = usePlayerStore(s => s.playerMode);
  const playbackRate = usePlayerStore(s => s.playbackRate);
  const setPlaybackRate = usePlayerStore(s => s.setPlaybackRate);
  const setVolume = usePlayerStore(s => s.setVolume);
  const toggleShuffle = usePlayerStore(s => s.toggleShuffle);
  const toggleRepeat = usePlayerStore(s => s.toggleRepeat);
  const pause = usePlayerStore(s => s.pause);
  const resume = usePlayerStore(s => s.resume);
  const next = usePlayerStore(s => s.next);
  const previous = usePlayerStore(s => s.previous);
  const toggleKaraoke = usePlayerStore(s => s.toggleKaraoke);
  const setVocalBlend = usePlayerStore(s => s.setVocalBlend);
  const setSleepTimer = usePlayerStore(s => s.setSleepTimer);
  const clearSleepTimer = usePlayerStore(s => s.clearSleepTimer);

  const handleSeek = seek;
  const handleSetVolume = setVolume;
  const handleToggleShuffle = toggleShuffle;
  const handleToggleRepeat = toggleRepeat;
  const handlePlayPause = () => {
    if (isPlaying) pause();
    else resume();
  };
  const handleNext = next;
  const handlePrevious = previous;
  const handleToggleKaraoke = toggleKaraoke;
  const handleSetSleepTimer = setSleepTimer;
  const handleClearSleepTimer = clearSleepTimer;
  const handleThumbsUp = async () => {
    if (!currentTrack) return;
    await useSessionStore.getState().ensureActiveSession();
    sendSignal({
      signalType: 'THUMBS_UP',
      trackId: currentTrack.Id,
      context: {
        positionPct: 0,
        elapsedSec: 0,
        autoplay: false,
        selectedByUser: true,
        timeOfDay: currentTimeOfDay(),
      },
    });
    toast.add('success', 'Thanks — more like this');
  };
  const handleThumbsDown = async () => {
    if (!currentTrack) return;
    await useSessionStore.getState().ensureActiveSession();
    sendSignal({
      signalType: 'THUMBS_DOWN',
      trackId: currentTrack.Id,
      context: {
        positionPct: 0,
        elapsedSec: 0,
        autoplay: false,
        selectedByUser: true,
        timeOfDay: currentTimeOfDay(),
      },
    });
    next();
    toast.add('info', 'Skipping...');
  };

  // Opening the full player pushes a history entry so the hardware/browser Back button closes it.
  // Closing it any other way (chevron, backdrop) must pop that same entry, otherwise the next Back
  // press visibly does nothing. The ref guards against popping twice when Back itself closed it.
  const fullPlayerHistoryEntryPushed = useRef(false);
  useEffect(() => {
    if (!showFullPlayer) return;
    history.pushState(null, '');
    fullPlayerHistoryEntryPushed.current = true;
    const handlePopState = () => {
      fullPlayerHistoryEntryPushed.current = false;
      setShowFullPlayer(false);
    };
    globalThis.addEventListener('popstate', handlePopState);
    return () => globalThis.removeEventListener('popstate', handlePopState);
  }, [showFullPlayer]);

  const closeFullPlayer = () => {
    if (fullPlayerHistoryEntryPushed.current) {
      fullPlayerHistoryEntryPushed.current = false;
      history.back();
    } else {
      setShowFullPlayer(false);
    }
  };

  const dismissFullPlayerForNavigation = () => setShowFullPlayer(false);

  const { pathname } = useLocation();
  const isInitialLocationRef = useRef(true);
  useEffect(() => {
    if (isInitialLocationRef.current) {
      isInitialLocationRef.current = false;
      return;
    }
    setShowLyricsView(false);
    setShowQueue(false);
    setShowSleepModal(false);
    setShowDevices(false);
    setShowGroupSync(false);
  }, [pathname]);

  useEffect(() => {
    if (playerError) {
      const unsupported = playerError instanceof UnsupportedFormatError;
      if (unsupported) {
        toast.add('error', 'Track format not supported — skipping', 8000);
      } else {
        toast.add('error', 'Playback failed — check your connection', 8000, {
          label: 'Retry',
          onClick: () => {
            void usePlayerStore.getState().retryCurrentTrack();
          },
        });
      }
    }
  }, [playerError]);

  const prevKaraokeStateRef = useRef(karaokeStatus?.state);
  const lastProcessingToastRef = useRef(0);
  useEffect(() => {
    const prev = prevKaraokeStateRef.current;
    const curr = karaokeStatus?.state;
    prevKaraokeStateRef.current = curr;

    if (curr === 'PROCESSING' && prev !== 'PROCESSING') {
      const now = Date.now();
      if (now - lastProcessingToastRef.current > 3000) {
        lastProcessingToastRef.current = now;
        toast.add('info', 'Processing vocals...');
      }
    } else if (curr === 'FAILED') {
      const msg = karaokeStatus?.message;
      toast.add('error', msg ? `Vocal separation failed: ${msg}` : 'Vocal separation failed');
    }
  }, [karaokeStatus?.state, karaokeStatus?.message]);

  useEffect(() => {
    if (karaokeStatus?.state !== 'PROCESSING' || !currentTrack?.Id) return;

    const client = useAuthStore.getState().client;
    if (!client) return;

    let sseUrl: string;
    try {
      sseUrl = client.getKaraokeStatusStreamUrl(currentTrack.Id);
    } catch {
      return;
    }

    let closed = false;
    let reconnectAttempts = 0;
    const MAX_RECONNECTS = 3;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let pollInterval: ReturnType<typeof setInterval> | null = null;
    let currentEs: EventSource | null = null;

    const onMessage = (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data as string) as { state: string };
        usePlayerStore
          .getState()
          .refreshKaraokeStatus()
          .catch(() => {});
        if (data.state === 'READY' || data.state === 'FAILED') {
          closed = true;
          currentEs?.close();
          currentEs = null;
        }
      } catch {
        // ignore malformed events
      }
    };

    const pollKaraokeStatus = () => {
      usePlayerStore
        .getState()
        .refreshKaraokeStatus()
        .catch(() => {});
    };

    const onError = () => {
      currentEs?.close();
      currentEs = null;
      if (closed) return;
      if (reconnectAttempts >= MAX_RECONNECTS) {
        pollInterval = setInterval(pollKaraokeStatus, 5000);
        return;
      }
      reconnectAttempts++;
      reconnectTimer = setTimeout(() => {
        if (closed) return;
        const es = new EventSource(sseUrl);
        es.addEventListener('status', onMessage);
        es.onerror = onError;
        currentEs = es;
      }, 2000 * reconnectAttempts);
    };

    const es = new EventSource(sseUrl);
    es.addEventListener('status', onMessage);
    es.onerror = onError;
    currentEs = es;

    return () => {
      closed = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (pollInterval) clearInterval(pollInterval);
      currentEs?.close();
      currentEs = null;
    };
  }, [karaokeStatus?.state, currentTrack?.Id]);

  useEffect(() => {
    if (!sleepTimer.endTime) {
      setSleepMinutesLeft(0);
      return;
    }

    const updateRemaining = () => {
      const remaining = Math.ceil((sleepTimer.endTime! - Date.now()) / 60000);
      setSleepMinutesLeft(Math.max(0, remaining));
    };

    updateRemaining();
    const interval = setInterval(updateRemaining, 10000);
    return () => clearInterval(interval);
  }, [sleepTimer.endTime]);

  if (!currentTrack) {
    return null;
  }

  const imageUrl = getTrackImageUrl(getImageUrl, {
    albumId: currentTrack.AlbumId,
    albumPrimaryImageTag: currentTrack.AlbumPrimaryImageTag,
    trackId: currentTrack.Id,
    maxWidth: 64,
    maxHeight: 64,
  });

  const imageUrlLarge = getTrackImageUrl(getImageUrl, {
    albumId: currentTrack.AlbumId,
    albumPrimaryImageTag: currentTrack.AlbumPrimaryImageTag,
    trackId: currentTrack.Id,
    maxWidth: 400,
    maxHeight: 400,
  });

  const karaokeProcessing = karaokeEnabled && karaokeStatus?.state === 'PROCESSING';
  let karaokeClass: string;
  if (isKaraokeMode) karaokeClass = 'text-accent';
  else if (karaokeProcessing) karaokeClass = 'text-accent animate-pulse';
  else karaokeClass = 'text-text-secondary hover:text-text-primary';
  let karaokeAriaLabel: string;
  if (isKaraokeMode) karaokeAriaLabel = 'Disable karaoke mode';
  else if (karaokeProcessing) karaokeAriaLabel = 'Cancel karaoke processing';
  else karaokeAriaLabel = 'Enable karaoke mode';
  const karaokeAriaPressed = isKaraokeMode || karaokeEnabled;
  const karaokeAriaBusy = karaokeStatus?.state === 'PROCESSING';

  return (
    <div
      data-testid="player-bar"
      className={cn(
        'z-player border-border bg-bg-secondary px-safe md:left-sidebar md:pb-safe bottom-above-tab-bar fixed right-0 left-0 border-t',
        showFullPlayer && 'invisible'
      )}
    >
      <div className="hidden md:block">
        <SeekBar onSeek={handleSeek} />
      </div>

      <MiniBarProgress />

      {/* Mobile mini-bar */}
      <div className="flex w-full items-center gap-3 p-2 px-3 md:hidden">
        <button
          type="button"
          className="focus-visible:ring-accent flex min-w-0 flex-1 cursor-pointer items-center gap-3 rounded focus-visible:ring-2 focus-visible:outline-none"
          onClick={() => setShowFullPlayer(true)}
          aria-label="Open player"
        >
          <img
            src={hasImageError ? getImagePlaceholder() : imageUrl}
            alt={currentTrack.Name}
            className="h-11 w-11 shrink-0 rounded object-cover"
          />
          <div className="min-w-0 flex-1">
            <MarqueeText
              data-testid="current-track-title"
              className="text-text-primary text-sm font-medium"
            >
              {currentTrack.Name}
            </MarqueeText>
            <p data-testid="current-track-artist" className="text-text-secondary truncate text-xs">
              {currentTrack.Artists?.[0] ?? 'Unknown Artist'}
            </p>
          </div>
        </button>
        <div role="toolbar" className="flex shrink-0 items-center gap-1">
          <FavoriteButton
            itemId={currentTrack.Id}
            itemType="track"
            isFavorite={getIsFavorite(currentTrack)}
            className="flex min-h-11 min-w-11 items-center justify-center"
          />
          <button
            type="button"
            data-testid="play-pause-button"
            onClick={handlePlayPause}
            className="bg-accent text-text-on-accent hover:bg-accent-hover focus-visible:ring-accent flex h-11 w-11 shrink-0 items-center justify-center rounded-full transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label={isPlaying ? 'Pause' : 'Play'}
            aria-busy={isLoading}
          >
            <PlayPauseIcon isLoading={isLoading} isPlaying={isPlaying} className="h-4 w-4" />
          </button>
        </div>
      </div>

      <div className="mx-auto hidden max-w-7xl items-center gap-4 p-2 px-4 md:flex">
        <TrackInfo
          track={currentTrack}
          hasImageError={hasImageError}
          onImageError={onImageError}
          imageUrl={imageUrl}
          isRadioMode={!!activeSession?.isRadioMode}
        />

        <PlaybackControls
          isPlaying={isPlaying}
          isShuffle={isShuffle}
          repeatMode={repeatMode}
          onPlayPause={handlePlayPause}
          onNext={handleNext}
          onPrevious={handlePrevious}
          onToggleShuffle={handleToggleShuffle}
          onToggleRepeat={handleToggleRepeat}
          isAudiobook={playerMode === 'audiobook'}
          isLoading={isLoading}
          onSkipBackward={() => skipBy(-15)}
          onSkipForward={() => skipBy(30)}
        />

        {playerMode === 'audiobook' && (
          <button
            type="button"
            data-testid="audiobook-speed"
            onClick={() => setPlaybackRate(nextAudiobookSpeed(playbackRate), 'book')}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent hidden shrink-0 rounded-full px-2 py-1 text-xs font-semibold transition-colors focus-visible:ring-2 focus-visible:outline-none sm:flex"
            aria-label={`Playback speed ${playbackRate}x`}
          >
            {playbackRate}x
          </button>
        )}

        <div className="hidden shrink-0 items-center justify-end gap-1 sm:flex md:flex-1 md:gap-2">
          <TimeDisplay />

          {currentTrack && (
            <>
              <button
                type="button"
                onClick={handleThumbsUp}
                className="text-text-secondary hover:text-success focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
                aria-label="Thumbs up"
                title="Like this track"
              >
                <ThumbsUp className="h-4 w-4" />
              </button>
              <button
                type="button"
                onClick={handleThumbsDown}
                className="text-text-secondary hover:text-error focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
                aria-label="Thumbs down"
                title="Skip and avoid"
              >
                <ThumbsDown className="h-4 w-4" />
              </button>
            </>
          )}

          <button
            type="button"
            data-testid={PLAYER_TEST_IDS.RADIO_BUTTON}
            onClick={() => {
              if (currentTrack) void startSession(currentTrack.Id);
            }}
            disabled={isSessionStarting || !isOnline}
            className={cn(
              'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50',
              activeSession?.isRadioMode
                ? 'text-accent'
                : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label="Start radio from this song"
            title={isOnline ? 'Start radio from this song' : 'Radio needs a connection'}
          >
            <Radio className="h-4 w-4" />
          </button>

          <button
            type="button"
            data-testid={PLAYER_TEST_IDS.QUEUE_BUTTON}
            onClick={() => setShowQueue(true)}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Queue"
            title="Queue"
          >
            <ListMusic className="h-4 w-4" />
          </button>

          <button
            type="button"
            onClick={handleToggleKaraoke}
            disabled={isLoading || isKaraokeTransitioning}
            className={cn(
              'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50',
              karaokeClass
            )}
            aria-label={karaokeAriaLabel}
            aria-pressed={karaokeAriaPressed}
            aria-busy={karaokeAriaBusy}
            title={(() => {
              if (isLoading) return 'Wait for track to load';
              if (karaokeProcessing) return 'Processing karaoke... Click to cancel';
              if (karaokeStatus?.state === 'PROCESSING') return 'Processing karaoke in background';
              if (isKaraokeTransitioning) return 'Switching...';
              return isKaraokeMode ? 'Karaoke on' : 'Karaoke';
            })()}
          >
            <Mic className="h-4 w-4" />
          </button>

          <KaraokeBlendSlider
            active={isKaraokeMode}
            value={vocalBlend}
            disabled={isLoading || isKaraokeTransitioning}
            onChange={setVocalBlend}
          />

          <button
            type="button"
            onClick={() => setShowLyricsView(true)}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Show lyrics"
            title="Show lyrics"
          >
            <AlignLeft className="h-4 w-4" />
          </button>

          <button
            type="button"
            onClick={() => setShowSleepModal(true)}
            className={cn(
              'focus-visible:ring-accent relative rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
              sleepTimer.endTime ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label="Sleep timer"
            title="Sleep timer"
            data-testid={PLAYER_TEST_IDS.SLEEP_TIMER_BUTTON}
          >
            <Timer className="h-4 w-4" />
            {sleepTimer.endTime && (
              <span className="bg-accent text-text-on-accent absolute -top-1 -right-1 flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[10px] leading-none">
                {sleepMinutesLeft > 0 ? sleepMinutesLeft : '<1'}
              </span>
            )}
          </button>

          <button
            type="button"
            onClick={() => setShowGroupSync(true)}
            className={cn(
              'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
              groupMode === 'group' ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label="Group listen"
            title={groupMode === 'group' ? 'In group' : 'Group listen'}
          >
            <Users className="h-4 w-4" />
          </button>

          <button
            type="button"
            onClick={() => setShowDevices(true)}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Devices"
            title="My devices"
          >
            <MonitorSmartphone className="h-4 w-4" />
          </button>

          <VolumeControls volume={volume} onVolumeChange={handleSetVolume} />
        </div>
      </div>

      <DevicesPanel isOpen={showDevices} onClose={() => setShowDevices(false)} />
      <GroupSyncPanel isOpen={showGroupSync} onClose={() => setShowGroupSync(false)} />
      {showQueue && (
        <Suspense fallback={null}>
          <QueuePanel isOpen={showQueue} onClose={() => setShowQueue(false)} />
        </Suspense>
      )}
      <SleepTimerModal
        isOpen={showSleepModal}
        onClose={() => setShowSleepModal(false)}
        currentMinutes={sleepTimer.minutes}
        hasActiveTimer={!!sleepTimer.endTime}
        onSetTimer={handleSetSleepTimer}
        onClearTimer={handleClearSleepTimer}
      />

      <LyricsView isOpen={showLyricsView} onClose={() => setShowLyricsView(false)} />

      {showFullPlayer && (
        <MobileFullPlayer
          track={currentTrack}
          imageUrl={imageUrlLarge}
          hasImageError={hasImageError}
          isPlaying={isPlaying}
          isLoading={isLoading}
          isShuffle={isShuffle}
          repeatMode={repeatMode}
          isKaraokeMode={isKaraokeMode}
          isKaraokeTransitioning={isKaraokeTransitioning}
          karaokeStatus={karaokeStatus}
          vocalBlend={vocalBlend}
          hasSleepTimer={!!sleepTimer.endTime}
          sleepMinutesLeft={sleepMinutesLeft}
          showThumbs
          onClose={closeFullPlayer}
          onNavigate={dismissFullPlayerForNavigation}
          onPlayPause={handlePlayPause}
          onNext={handleNext}
          onPrevious={handlePrevious}
          onToggleShuffle={handleToggleShuffle}
          onToggleRepeat={handleToggleRepeat}
          onToggleKaraoke={handleToggleKaraoke}
          onVocalBlendChange={setVocalBlend}
          onOpenSleepTimer={() => setShowSleepModal(true)}
          onOpenQueue={() => setShowQueue(true)}
          onSeek={handleSeek}
          onThumbsUp={handleThumbsUp}
          onThumbsDown={handleThumbsDown}
          isRadioMode={!!activeSession?.isRadioMode}
          canStartRadio={isOnline && !isSessionStarting}
          onStartRadio={() => {
            if (currentTrack) void startSession(currentTrack.Id);
          }}
        />
      )}
    </div>
  );
}
