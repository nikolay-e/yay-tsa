import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { AudioItem } from '@yay-tsa/core';
import { Mic, Timer, AlignLeft, ThumbsUp, ThumbsDown } from 'lucide-react';
import { FavoriteButton } from '@/features/library/components/FavoriteButton';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { getTrackImageUrl } from '@/shared/utils/track-image';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { PLAYER_TEST_IDS } from '@/shared/testing/test-ids';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { useAuthStore } from '@/features/auth/stores/auth.store';
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
}: Readonly<{
  track: AudioItem;
  hasImageError: boolean;
  onImageError: () => void;
  imageUrl: string;
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
        {track.AlbumId ? (
          <Link
            to={`/albums/${track.AlbumId}`}
            data-testid="current-track-title"
            className="text-text-primary block truncate font-medium hover:underline"
          >
            {track.Name}
          </Link>
        ) : (
          <p data-testid="current-track-title" className="text-text-primary truncate font-medium">
            {track.Name}
          </p>
        )}
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

  const handleSeek = useCallback((seconds: number) => usePlayerStore.getState().seek(seconds), []);
  const handleSetVolume = useCallback((v: number) => usePlayerStore.getState().setVolume(v), []);
  const handleToggleShuffle = useCallback(() => usePlayerStore.getState().toggleShuffle(), []);
  const handleToggleRepeat = useCallback(() => usePlayerStore.getState().toggleRepeat(), []);
  const handlePlayPause = useCallback(() => {
    if (usePlayerStore.getState().isPlaying) {
      usePlayerStore.getState().pause();
    } else {
      usePlayerStore.getState().resume();
    }
  }, []);
  const handleNext = useCallback(() => {
    usePlayerStore.getState().next();
  }, []);
  const handlePrevious = useCallback(() => {
    usePlayerStore.getState().previous();
  }, []);
  const handleToggleKaraoke = useCallback(() => {
    usePlayerStore.getState().toggleKaraoke();
  }, []);
  const handleSetSleepTimer = useCallback(
    (m: number) => usePlayerStore.getState().setSleepTimer(m),
    []
  );
  const handleClearSleepTimer = useCallback(() => usePlayerStore.getState().clearSleepTimer(), []);
  const handleThumbsUp = useCallback(() => {
    if (!currentTrack) return;
    sendSignal({
      signalType: 'THUMBS_UP',
      trackId: currentTrack.Id,
      context: {
        positionPct: 0,
        elapsedSec: 0,
        autoplay: false,
        selectedByUser: true,
        timeOfDay: new Date().toISOString(),
      },
    });
    toast.add('success', 'Liked');
  }, [currentTrack, sendSignal]);
  const handleThumbsDown = useCallback(() => {
    if (!currentTrack) return;
    sendSignal({
      signalType: 'THUMBS_DOWN',
      trackId: currentTrack.Id,
      context: {
        positionPct: 0,
        elapsedSec: 0,
        autoplay: false,
        selectedByUser: true,
        timeOfDay: new Date().toISOString(),
      },
    });
    usePlayerStore.getState().next();
    toast.add('info', 'Skipping...');
  }, [currentTrack, sendSignal]);

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

    let es = new EventSource(sseUrl);
    let closed = false;
    let reconnectAttempts = 0;
    const MAX_RECONNECTS = 3;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

    const onMessage = (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data as string) as { state: string };
        void usePlayerStore.getState().refreshKaraokeStatus();
        if (data.state === 'READY' || data.state === 'FAILED') {
          closed = true;
          es.close();
        }
      } catch {
        // ignore malformed events
      }
    };

    let pollInterval: ReturnType<typeof setInterval> | null = null;

    const onError = () => {
      es.close();
      if (closed) return;
      if (reconnectAttempts >= MAX_RECONNECTS) {
        pollInterval = setInterval(() => {
          void usePlayerStore.getState().refreshKaraokeStatus();
        }, 5000);
        return;
      }
      reconnectAttempts++;
      reconnectTimer = setTimeout(() => {
        if (closed) return;
        es = new EventSource(sseUrl);
        es.onmessage = onMessage;
        es.onerror = onError;
      }, 2000 * reconnectAttempts);
    };

    es.onmessage = onMessage;
    es.onerror = onError;

    return () => {
      closed = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (pollInterval) clearInterval(pollInterval);
      es.close();
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

  return (
    <div
      data-testid="player-bar"
      className="z-player border-border bg-bg-secondary px-safe md:left-sidebar md:pb-safe bottom-above-tab-bar fixed right-0 left-0 border-t"
    >
      <SeekBar onSeek={handleSeek} />

      <div className="mx-auto flex max-w-7xl items-center gap-4 p-2 px-4">
        <TrackInfo
          track={currentTrack}
          hasImageError={hasImageError}
          onImageError={onImageError}
          imageUrl={imageUrl}
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

        <div className="flex shrink-0 items-center justify-end gap-1 md:flex-1 md:gap-2">
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
              isKaraokeMode
                ? 'text-accent'
                : karaokeEnabled && karaokeStatus?.state === 'PROCESSING'
                  ? 'text-accent animate-pulse'
                  : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label={
              karaokeStatus?.state === 'PROCESSING'
                ? 'Cancel karaoke processing'
                : isKaraokeMode
                  ? 'Disable karaoke mode'
                  : 'Enable karaoke mode'
            }
            aria-pressed={isKaraokeMode ? true : karaokeEnabled ? 'mixed' : false}
            title={(() => {
              if (isLoading) return 'Wait for track to load';
              if (karaokeStatus?.state === 'PROCESSING')
                return 'Processing karaoke... Click to cancel';
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
    </div>
  );
}
