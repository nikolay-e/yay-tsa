import React, { useEffect, useState } from 'react';
import {
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Volume2,
  VolumeX,
  Shuffle,
  Repeat,
  Repeat1,
  Mic,
  MicOff,
  Timer,
  X,
} from 'lucide-react';
import { useImageUrl, getImagePlaceholder } from '@/features/auth/hooks/useImageUrl';
import { formatDuration } from '@/shared/utils/time';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { useTimingStore } from '../stores/timing.store';
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

export function PlayerBar() {
  const [hasImageError, setHasImageError] = useState(false);
  const [showSleepModal, setShowSleepModal] = useState(false);
  const currentTrack = useCurrentTrack();
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
    setHasImageError(false);
  }, [currentTrack?.Id]);

  useEffect(() => {
    if (karaokeStatus?.state !== 'PROCESSING') return;

    const interval = setInterval(() => {
      void refreshKaraokeStatus();
    }, 3000);

    return () => clearInterval(interval);
  }, [karaokeStatus?.state, refreshKaraokeStatus]);

  if (!currentTrack) {
    return null;
  }

  const imageUrl = currentTrack.AlbumPrimaryImageTag
    ? getImageUrl(currentTrack.AlbumId || currentTrack.Id, 'Primary', {
        tag: currentTrack.AlbumPrimaryImageTag,
        maxWidth: 64,
        maxHeight: 64,
      })
    : getImagePlaceholder();

  const artistName = currentTrack.Artists?.[0] || 'Unknown Artist';
  const progress = duration > 0 ? (currentTime / duration) * 100 : 0;

  const handleSeek = (e: React.MouseEvent<HTMLDivElement>) => {
    if (duration === 0) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const percent = (e.clientX - rect.left) / rect.width;
    seek(percent * duration);
  };

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setVolume(parseFloat(e.target.value));
  };

  const toggleMute = () => {
    setVolume(volume > 0 ? 0 : 1);
  };

  return (
    <div
      data-testid="player-bar"
      className="safe-area-bottom z-player border-border bg-bg-secondary fixed right-0 bottom-0 left-0 border-t"
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
            onError={() => setHasImageError(true)}
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

        <div className="flex items-center gap-2">
          <button
            onClick={toggleShuffle}
            className={cn(
              'hidden rounded-full p-2 transition-colors sm:flex',
              isShuffle ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label="Shuffle"
          >
            <Shuffle className="h-4 w-4" />
          </button>

          <button
            data-testid="previous-button"
            onClick={() => previous()}
            className="text-text-secondary hover:text-text-primary p-2 transition-colors"
            aria-label="Previous"
          >
            <SkipBack className="h-5 w-5" fill="currentColor" />
          </button>

          <button
            data-testid="play-pause-button"
            onClick={() => (isPlaying ? pause() : resume())}
            className={cn(
              'bg-accent rounded-full p-3 text-white',
              'hover:bg-accent-hover transition-colors'
            )}
            aria-label={isPlaying ? 'Pause' : 'Play'}
          >
            {isPlaying ? (
              <Pause className="h-5 w-5" fill="currentColor" />
            ) : (
              <Play className="ml-0.5 h-5 w-5" fill="currentColor" />
            )}
          </button>

          <button
            data-testid="next-button"
            onClick={() => next()}
            className="text-text-secondary hover:text-text-primary p-2 transition-colors"
            aria-label="Next"
          >
            <SkipForward className="h-5 w-5" fill="currentColor" />
          </button>

          <button
            onClick={toggleRepeat}
            className={cn(
              'hidden rounded-full p-2 transition-colors sm:flex',
              repeatMode !== 'off' ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label="Repeat"
          >
            {repeatMode === 'one' ? (
              <Repeat1 className="h-4 w-4" />
            ) : (
              <Repeat className="h-4 w-4" />
            )}
          </button>
        </div>

        <div className="hidden flex-1 items-center justify-end gap-2 md:flex">
          <span className="text-text-tertiary text-xs tabular-nums">
            <span data-testid="current-time">{formatDuration(currentTime)}</span> /{' '}
            <span data-testid="total-time">{formatDuration(duration)}</span>
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
              title={isKaraokeMode ? 'Karaoke mode on' : 'Karaoke mode'}
            >
              {isKaraokeMode ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
            </button>
          )}

          <button
            onClick={() => setShowSleepModal(true)}
            className={cn(
              'relative rounded-full p-2 transition-colors',
              sleepTimer.endTime ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
            )}
            aria-label="Sleep timer"
          >
            <Timer className="h-4 w-4" />
            {sleepTimer.endTime && (
              <span className="bg-accent absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full text-[10px] text-white">
                {Math.ceil((sleepTimer.endTime - Date.now()) / 60000)}
              </span>
            )}
          </button>

          <button
            onClick={toggleMute}
            className="text-text-secondary hover:text-text-primary p-2 transition-colors"
            aria-label={volume > 0 ? 'Mute' : 'Unmute'}
          >
            {volume > 0 ? <Volume2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
          </button>

          <input
            data-testid="volume-slider"
            type="range"
            min="0"
            max="1"
            step="0.01"
            value={volume}
            onChange={handleVolumeChange}
            className="bg-bg-tertiary accent-accent h-1 w-24 cursor-pointer appearance-none rounded-full"
            aria-label="Volume"
          />
        </div>
      </div>

      {showSleepModal && (
        <div
          role="presentation"
          className="z-modal-backdrop fixed inset-0 flex items-center justify-center bg-black/50"
          onClick={() => setShowSleepModal(false)}
          onKeyDown={e => e.key === 'Escape' && setShowSleepModal(false)}
        >
          {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions, jsx-a11y/click-events-have-key-events */}
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="sleep-timer-title"
            className="bg-bg-secondary w-80 rounded-lg p-6 shadow-lg"
            onClick={e => e.stopPropagation()}
          >
            <div className="mb-4 flex items-center justify-between">
              <h3 id="sleep-timer-title" className="text-text-primary text-lg font-semibold">
                Sleep Timer
              </h3>
              <button
                onClick={() => setShowSleepModal(false)}
                className="text-text-secondary hover:text-text-primary p-1"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <div className="space-y-2">
              {[5, 10, 15, 30, 45, 60].map(mins => (
                <button
                  key={mins}
                  onClick={() => {
                    setSleepTimer(mins);
                    setShowSleepModal(false);
                    toast.add('info', `Sleep timer set for ${mins} minutes`);
                  }}
                  className={cn(
                    'w-full rounded-md p-3 text-left transition-colors',
                    sleepTimer.minutes === mins
                      ? 'bg-accent text-white'
                      : 'bg-bg-tertiary text-text-primary hover:bg-bg-hover'
                  )}
                >
                  {mins} minutes
                </button>
              ))}

              {sleepTimer.endTime && (
                <button
                  onClick={() => {
                    clearSleepTimer();
                    setShowSleepModal(false);
                    toast.add('info', 'Sleep timer cancelled');
                  }}
                  className="bg-error/10 text-error hover:bg-error/20 w-full rounded-md p-3 text-left transition-colors"
                >
                  Cancel timer
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
