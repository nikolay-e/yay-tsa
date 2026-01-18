import { Play, Pause, SkipBack, SkipForward, Shuffle, Repeat, Repeat1 } from 'lucide-react';
import type { RepeatMode } from '@yaytsa/core';
import { cn } from '@/shared/utils/cn';

interface PlaybackControlsProps {
  isPlaying: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
  onPlayPause: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onToggleShuffle: () => void;
  onToggleRepeat: () => void;
}

export function PlaybackControls({
  isPlaying,
  isShuffle,
  repeatMode,
  onPlayPause,
  onNext,
  onPrevious,
  onToggleShuffle,
  onToggleRepeat,
}: PlaybackControlsProps) {
  return (
    <div className="flex items-center gap-2">
      <button
        onClick={onToggleShuffle}
        className={cn(
          'hidden rounded-full p-2 transition-colors sm:flex',
          isShuffle ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
        )}
        aria-label="Shuffle"
        aria-pressed={isShuffle}
      >
        <Shuffle className="h-4 w-4" />
      </button>

      <button
        data-testid="previous-button"
        onClick={onPrevious}
        className="text-text-secondary hover:text-text-primary p-2 transition-colors"
        aria-label="Previous"
      >
        <SkipBack className="h-5 w-5" fill="currentColor" />
      </button>

      <button
        data-testid="play-pause-button"
        onClick={onPlayPause}
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
        onClick={onNext}
        className="text-text-secondary hover:text-text-primary p-2 transition-colors"
        aria-label="Next"
      >
        <SkipForward className="h-5 w-5" fill="currentColor" />
      </button>

      <button
        onClick={onToggleRepeat}
        className={cn(
          'hidden rounded-full p-2 transition-colors sm:flex',
          repeatMode !== 'off' ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
        )}
        aria-label={`Repeat: ${repeatMode}`}
        aria-pressed={repeatMode !== 'off'}
      >
        {repeatMode === 'one' ? <Repeat1 className="h-4 w-4" /> : <Repeat className="h-4 w-4" />}
      </button>
    </div>
  );
}
