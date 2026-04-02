import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { AudioItem } from '@yay-tsa/core';
import { Mic, Timer, AlignLeft, ThumbsUp, ThumbsDown, Play, Pause, Radio } from 'lucide-react';
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
  useSleepTimer,
} from '../stores/player.store';
import { useActiveSession, useSessionActions } from '../stores/session-store';
import { useAlbumColors } from '../hooks/useAlbumColors';
import { useSignalEmitter } from '../hooks/useSignalEmitter';
import { useDjAutoRefill } from '../hooks/useDjAutoRefill';
import { MobileFullPlayer } from './MobileFullPlayer';
import { SeekBar, TimeDisplay } from './SeekBar';
import { LyricsView } from './LyricsView';
import { SleepTimerModal } from './SleepTimerModal';
import { VolumeControls } from './VolumeControls';
import { PlaybackControls } from './PlaybackControls';

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
      <FavoriteButton itemId={track.Id} isFavorite={track.UserData?.IsFavorite ?? false} />
    </div>
  );
}

export function PlayerBar() {
  const [showSleepModal, setShowSleepModal] = useState(false);
  const [showLyricsView, setShowLyricsView] = useState(false);
  const [showFullPlayer, setShowFullPlayer] = useState(false);
  const [sleepMinutesLeft, setSleepMinutesLeft] = useState(0);
  const activeSession = useActiveSession();
  const { sendSignal } = useSessionActions();
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
  const sleepTimer = useSleepTimer();
  useAlbumColors();
  useSignalEmitter();
  useDjAutoRefill();
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    currentTrack?.Id ?? '',
    currentTrack?.AlbumPrimaryImageTag,
    currentTrack?.AlbumId
  );

  const { getImageUrl } = useImageUrl();

  const seek = usePlayerStore(s => s.seek);
  const setVolume = usePlayerStore(s => s.setVolume);
  const toggleShuffle = usePlayerStore(s => s.toggleShuffle);
  const toggleRepeat = usePlayerStore(s => s.toggleRepeat);
  const pause = usePlayerStore(s => s.pause);
  const resume = usePlayerStore(s => s.resume);
  const next = usePlayerStore(s => s.next);
  const previous = usePlayerStore(s => s.previous);
  const toggleKaraoke = usePlayerStore(s => s.toggleKaraoke);
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
  const handleThumbsUp = () => {
    if (!currentTrack) return;
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
    toast.add('success', 'Liked');
  };
  const handleThumbsDown = () => {
    if (!currentTrack) return;
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

  useEffect(() => {
    if (!showFullPlayer) return;
    history.pushState(null, '');
    const handlePopState = () => setShowFullPlayer(false);
    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, [showFullPlayer]);

  useEffect(() => {
    if (playerError) {
      toast.add('error', `Playback error: ${playerError.message}`);
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
    if (karaokeStatus?.state !== 'PROCESSING' || !currentTrack) return;

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
      className="z-player border-border bg-bg-secondary px-safe md:left-sidebar md:pb-safe bottom-above-tab-bar fixed right-0 left-0 border-t"
    >
      <div className="hidden md:block">
        <SeekBar onSeek={handleSeek} />
      </div>

      {/* Mobile mini-bar */}
      <button
        type="button"
        className="flex w-full cursor-pointer items-center gap-3 p-2 px-3 md:hidden"
        onClick={() => setShowFullPlayer(true)}
        aria-label="Open player"
      >
        <img
          src={hasImageError ? getImagePlaceholder() : imageUrl}
          alt={currentTrack.Name}
          className="h-11 w-11 shrink-0 rounded object-cover"
        />
        <div className="min-w-0 flex-1">
          <MarqueeText className="text-text-primary text-sm font-medium">
            {currentTrack.Name}
          </MarqueeText>
          <p className="text-text-secondary truncate text-xs">
            {currentTrack.Artists?.[0] ?? 'Unknown Artist'}
          </p>
        </div>
        <span
          className="flex shrink-0 items-center gap-1"
          onClick={e => e.stopPropagation()}
          onKeyDown={e => e.stopPropagation()}
        >
          <FavoriteButton
            itemId={currentTrack.Id}
            isFavorite={currentTrack.UserData?.IsFavorite ?? false}
          />
          <button
            type="button"
            onClick={handlePlayPause}
            className="bg-accent text-text-on-accent hover:bg-accent-hover focus-visible:ring-accent flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label={isPlaying ? 'Pause' : 'Play'}
          >
            {isPlaying ? (
              <Pause className="h-4 w-4" fill="currentColor" />
            ) : (
              <Play className="ml-0.5 h-4 w-4" fill="currentColor" />
            )}
          </button>
        </span>
      </button>

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
        />

        <div className="hidden shrink-0 items-center justify-end gap-1 sm:flex md:flex-1 md:gap-2">
          <TimeDisplay />

          {activeSession && (
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
            {sleepTimer.endTime && sleepMinutesLeft > 0 && (
              <span className="bg-accent text-text-on-accent absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full text-[10px]">
                {sleepMinutesLeft}
              </span>
            )}
          </button>

          <VolumeControls volume={volume} onVolumeChange={handleSetVolume} />
        </div>
      </div>

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
          isShuffle={isShuffle}
          repeatMode={repeatMode}
          isKaraokeMode={isKaraokeMode}
          isKaraokeTransitioning={isKaraokeTransitioning}
          karaokeStatus={karaokeStatus}
          hasSleepTimer={!!sleepTimer.endTime}
          sleepMinutesLeft={sleepMinutesLeft}
          onClose={() => setShowFullPlayer(false)}
          onPlayPause={handlePlayPause}
          onNext={handleNext}
          onPrevious={handlePrevious}
          onToggleShuffle={handleToggleShuffle}
          onToggleRepeat={handleToggleRepeat}
          onToggleKaraoke={handleToggleKaraoke}
          onOpenSleepTimer={() => setShowSleepModal(true)}
          onSeek={handleSeek}
        />
      )}
    </div>
  );
}
