import React, { useEffect, useState } from 'react';
import { Mic, MicOff, Timer, AlignLeft } from 'lucide-react';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { getTrackImageUrl } from '@/shared/utils/track-image';
import { formatSeconds } from '@/shared/utils/time';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { useImageErrorTracking } from '@/shared/hooks/useImageErrorTracking';
import { useTimingStore } from '../stores/playback-timing.store';
import { useLyrics } from '../hooks/useLyrics';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
  useVolume,
  useIsShuffle,
  useRepeatMode,
  usePlayerError,
  useIsKaraokeMode,
  useKaraokeStatus,
  useSleepTimer,
} from '../stores/player.store';
import { LyricsView } from './LyricsView';
import { SleepTimerModal } from './SleepTimerModal';
import { VolumeControls } from './VolumeControls';
import { PlaybackControls } from './PlaybackControls';

export function PlayerBar() {
  const [showSleepModal, setShowSleepModal] = useState(false);
  const [showLyricsView, setShowLyricsView] = useState(false);
  const [sleepMinutesLeft, setSleepMinutesLeft] = useState(0);
  const currentTrack = useCurrentTrack();
  const { hasLyrics } = useLyrics();
  const isPlaying = useIsPlaying();
  const volume = useVolume();
  const isShuffle = useIsShuffle();
  const repeatMode = useRepeatMode();
  const playerError = usePlayerError();
  const isKaraokeMode = useIsKaraokeMode();
  const karaokeStatus = useKaraokeStatus();
  const sleepTimer = useSleepTimer();
  const currentTime = useTimingStore(s => s.currentTime);
  const duration = useTimingStore(s => s.duration);
  const { hasError: hasImageError, onError: onImageError } = useImageErrorTracking(
    currentTrack?.Id ?? '',
    currentTrack?.AlbumPrimaryImageTag,
    currentTrack?.AlbumId
  );

  const {
    pause,
    resume,
    next,
    previous,
    seek,
    setVolume,
    toggleShuffle,
    toggleRepeat,
    toggleKaraoke,
    refreshKaraokeStatus,
    setSleepTimer,
    clearSleepTimer,
  } = usePlayerStore();
  const { getImageUrl } = useImageUrl();

  useEffect(() => {
    if (playerError) {
      toast.add('error', `Playback error: ${playerError.message}`);
    }
  }, [playerError]);

  useEffect(() => {
    if (karaokeStatus?.state !== 'PROCESSING') return;

    const interval = setInterval(() => {
      void refreshKaraokeStatus();
    }, 3000);

    return () => clearInterval(interval);
  }, [karaokeStatus?.state, refreshKaraokeStatus]);

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

  const artistName = currentTrack.Artists?.[0] ?? 'Unknown Artist';
  const progress = duration > 0 ? (currentTime / duration) * 100 : 0;

  const handleSeek = (e: React.MouseEvent<HTMLDivElement>) => {
    if (duration === 0) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const percent = (e.clientX - rect.left) / rect.width;
    seek(percent * duration);
  };

  return (
    <div
      data-testid="player-bar"
      className="z-player border-border bg-bg-secondary pb-safe fixed right-0 bottom-0 left-0 border-t"
    >
      <div
        data-testid="seek-slider"
        role="slider"
        tabIndex={0}
        aria-label="Seek"
        aria-valuenow={Math.round(currentTime)}
        aria-valuemin={0}
        aria-valuemax={Math.round(duration)}
        className="bg-bg-tertiary h-1 cursor-pointer"
        onClick={handleSeek}
        onKeyDown={e => {
          if (duration === 0) return;
          if (e.key === 'ArrowLeft') seek(Math.max(0, currentTime - 5));
          if (e.key === 'ArrowRight') seek(Math.min(duration, currentTime + 5));
        }}
      >
        <div
          className="bg-accent h-full transition-[width] duration-100"
          style={{ width: `${progress}%` }}
        />
      </div>

      <div className="flex items-center gap-4 p-2 px-4">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <img
            src={hasImageError ? getImagePlaceholder() : imageUrl}
            alt={currentTrack.Name}
            className="h-12 w-12 shrink-0 rounded-sm object-cover"
            onError={onImageError}
          />
          <div className="min-w-0">
            <p data-testid="current-track-title" className="text-text-primary truncate font-medium">
              {currentTrack.Name}
            </p>
            <p data-testid="current-track-artist" className="text-text-secondary truncate text-sm">
              {artistName}
            </p>
          </div>
        </div>

        <PlaybackControls
          isPlaying={isPlaying}
          isShuffle={isShuffle}
          repeatMode={repeatMode}
          onPlayPause={() => (isPlaying ? pause() : resume())}
          onNext={() => next()}
          onPrevious={() => previous()}
          onToggleShuffle={toggleShuffle}
          onToggleRepeat={toggleRepeat}
        />

        <div className="flex shrink-0 items-center justify-end gap-1 md:flex-1 md:gap-2">
          <span className="text-text-tertiary hidden text-xs tabular-nums md:inline">
            <span data-testid="current-time">{formatSeconds(currentTime)}</span> /{' '}
            <span data-testid="total-time">{formatSeconds(duration)}</span>
          </span>

          {karaokeStatus?.state === 'PROCESSING' ? (
            <div className="text-accent p-2">
              <Mic className="h-4 w-4 animate-pulse" />
            </div>
          ) : (
            <button
              onClick={() => void toggleKaraoke()}
              className={cn(
                'rounded-full p-2 transition-colors',
                isKaraokeMode ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
              )}
              aria-label={isKaraokeMode ? 'Disable karaoke mode' : 'Enable karaoke mode'}
              aria-pressed={isKaraokeMode}
              title={isKaraokeMode ? 'Karaoke mode on' : 'Karaoke mode'}
            >
              {isKaraokeMode ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
            </button>
          )}

          <button
            onClick={() => setShowLyricsView(true)}
            disabled={!hasLyrics}
            className={cn(
              'rounded-full p-2 transition-colors',
              hasLyrics
                ? 'text-text-secondary hover:text-text-primary'
                : 'text-text-tertiary cursor-not-allowed opacity-50'
            )}
            aria-label="Show lyrics"
            aria-disabled={!hasLyrics}
            title={hasLyrics ? 'Show lyrics' : 'No lyrics available'}
          >
            <AlignLeft className="h-4 w-4" />
          </button>

          <button
            onClick={() => setShowSleepModal(true)}
            className={cn(
              'relative rounded-full p-2 transition-colors',
              sleepTimer.endTime ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label="Sleep timer"
          >
            <Timer className="h-4 w-4" />
            {sleepTimer.endTime && sleepMinutesLeft > 0 && (
              <span className="bg-accent absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full text-[10px] text-white">
                {sleepMinutesLeft}
              </span>
            )}
          </button>

          <div className="hidden md:block">
            <VolumeControls volume={volume} onVolumeChange={setVolume} />
          </div>
        </div>
      </div>

      <SleepTimerModal
        isOpen={showSleepModal}
        onClose={() => setShowSleepModal(false)}
        currentMinutes={sleepTimer.minutes}
        hasActiveTimer={!!sleepTimer.endTime}
        onSetTimer={setSleepTimer}
        onClearTimer={clearSleepTimer}
      />

      {showLyricsView && <LyricsView onClose={() => setShowLyricsView(false)} />}
    </div>
  );
}
