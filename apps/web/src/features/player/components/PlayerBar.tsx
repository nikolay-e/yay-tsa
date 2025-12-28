import React from 'react';
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
} from 'lucide-react';
import {
  usePlayerStore,
  useCurrentTrack,
  useIsPlaying,
  useVolume,
  useIsShuffle,
  useRepeatMode,
} from '../stores/player.store';
import { useTimingStore } from '../stores/timing.store';
import { useImageUrl, getImagePlaceholder } from '@/shared/utils/image';
import { formatDuration } from '@/shared/utils/time';
import { cn } from '@/shared/utils/cn';

export function PlayerBar() {
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const volume = useVolume();
  const isShuffle = useIsShuffle();
  const repeatMode = useRepeatMode();
  const currentTime = useTimingStore(s => s.currentTime);
  const duration = useTimingStore(s => s.duration);

  const { pause, resume, next, previous, seek, setVolume, toggleShuffle, toggleRepeat } =
    usePlayerStore();
  const { getImageUrl } = useImageUrl();

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
    <div className="safe-area-bottom fixed bottom-0 left-0 right-0 z-40 border-t border-border bg-bg-secondary">
      <div
        role="slider"
        tabIndex={0}
        aria-label="Seek"
        aria-valuenow={Math.round(currentTime)}
        aria-valuemin={0}
        aria-valuemax={Math.round(duration)}
        className="h-1 cursor-pointer bg-bg-tertiary"
        onClick={handleSeek}
        onKeyDown={e => {
          if (duration === 0) return;
          if (e.key === 'ArrowLeft') seek(Math.max(0, currentTime - 5));
          if (e.key === 'ArrowRight') seek(Math.min(duration, currentTime + 5));
        }}
      >
        <div
          className="h-full bg-accent transition-[width] duration-100"
          style={{ width: `${progress}%` }}
        />
      </div>

      <div className="flex items-center gap-md p-sm px-md">
        <div className="flex min-w-0 flex-1 items-center gap-sm">
          <img
            src={imageUrl}
            alt={currentTrack.Name}
            className="h-12 w-12 flex-shrink-0 rounded-sm object-cover"
          />
          <div className="min-w-0">
            <p className="truncate font-medium text-text-primary">{currentTrack.Name}</p>
            <p className="truncate text-sm text-text-secondary">{artistName}</p>
          </div>
        </div>

        <div className="flex items-center gap-sm">
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
            onClick={() => previous()}
            className="p-2 text-text-secondary transition-colors hover:text-text-primary"
            aria-label="Previous"
          >
            <SkipBack className="h-5 w-5" fill="currentColor" />
          </button>

          <button
            onClick={() => (isPlaying ? pause() : resume())}
            className={cn(
              'rounded-full bg-accent p-3 text-white',
              'transition-colors hover:bg-accent-hover'
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
            onClick={() => next()}
            className="p-2 text-text-secondary transition-colors hover:text-text-primary"
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

        <div className="hidden flex-1 items-center justify-end gap-sm md:flex">
          <span className="text-xs tabular-nums text-text-tertiary">
            {formatDuration(currentTime)} / {formatDuration(duration)}
          </span>

          <button
            onClick={toggleMute}
            className="p-2 text-text-secondary transition-colors hover:text-text-primary"
            aria-label={volume > 0 ? 'Mute' : 'Unmute'}
          >
            {volume > 0 ? <Volume2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
          </button>

          <input
            type="range"
            min="0"
            max="1"
            step="0.01"
            value={volume}
            onChange={handleVolumeChange}
            className="h-1 w-24 cursor-pointer appearance-none rounded-full bg-bg-tertiary accent-accent"
            aria-label="Volume"
          />
        </div>
      </div>
    </div>
  );
}
