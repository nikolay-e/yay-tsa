import { Play, Pause, SkipBack, SkipForward, Shuffle, Repeat, Repeat1 } from 'lucide-react';
import type { RepeatMode } from '@yay-tsa/core';
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
        type="button"
        onClick={onToggleShuffle}
        className={cn(
          'focus-visible:ring-accent hidden rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none sm:flex',
          isShuffle ? 'text-accent' : 'text-text-secondary hover:text-text-primary'
        )}
        aria-label="Shuffle"
        aria-pressed={isShuffle}
      >
        <Shuffle className="h-4 w-4" />
      </button>

      <button
        type="button"
        data-testid="previous-button"
        onClick={onPrevious}
        className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
        aria-label="Previous"
      >
        <SkipBack className="h-5 w-5" fill="currentColor" />
      </button>

      <button
        type="button"
        data-testid="play-pause-button"
        onClick={onPlayPause}
        className={cn(
          'bg-accent text-text-on-accent rounded-full p-3',
          'hover:bg-accent-hover transition-colors',
          'focus-visible:ring-accent focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none'
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
        type="button"
        data-testid="next-button"
        onClick={onNext}
        className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
        aria-label="Next"
      >
        <SkipForward className="h-5 w-5" fill="currentColor" />
      </button>

      <button
        type="button"
        onClick={onToggleRepeat}
        className={cn(
          'focus-visible:ring-accent hidden rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none sm:flex',
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
