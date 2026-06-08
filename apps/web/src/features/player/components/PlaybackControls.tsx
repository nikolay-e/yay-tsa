import {
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Shuffle,
  Repeat,
  Repeat1,
  RotateCcw,
  RotateCw,
} from 'lucide-react';
import type { RepeatMode } from '@yay-tsa/core';
import { cn } from '@/shared/utils/cn';

type PlaybackControlsProps = Readonly<{
  isPlaying: boolean;
  isShuffle: boolean;
  repeatMode: RepeatMode;
  onPlayPause: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onToggleShuffle: () => void;
  onToggleRepeat: () => void;
  isAudiobook?: boolean;
  onSkipBackward?: () => void;
  onSkipForward?: () => void;
}>;

export function PlaybackControls({
  isPlaying,
  isShuffle,
  repeatMode,
  onPlayPause,
  onNext,
  onPrevious,
  onToggleShuffle,
  onToggleRepeat,
  isAudiobook = false,
  onSkipBackward,
  onSkipForward,
}: PlaybackControlsProps) {
  return (
    <div className="flex items-center gap-2">
      {isAudiobook ? (
        <button
          type="button"
          data-testid="audiobook-back-15"
          onClick={onSkipBackward}
          className="text-text-secondary hover:text-text-primary focus-visible:ring-accent relative hidden rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none sm:flex"
          aria-label="Back 15 seconds"
        >
          <RotateCcw className="h-4 w-4" />
          <span className="absolute inset-0 flex items-center justify-center text-[8px] font-bold">
            15
          </span>
        </button>
      ) : (
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
      )}

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

      {isAudiobook ? (
        <button
          type="button"
          data-testid="audiobook-forward-30"
          onClick={onSkipForward}
          className="text-text-secondary hover:text-text-primary focus-visible:ring-accent relative hidden rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none sm:flex"
          aria-label="Forward 30 seconds"
        >
          <RotateCw className="h-4 w-4" />
          <span className="absolute inset-0 flex items-center justify-center text-[8px] font-bold">
            30
          </span>
        </button>
      ) : (
        <button
          type="button"
          onClick={onToggleRepeat}
          className={cn(
            'focus-visible:ring-accent hidden rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none sm:flex',
            repeatMode === 'off' ? 'text-text-secondary hover:text-text-primary' : 'text-accent'
          )}
          aria-label={`Repeat: ${repeatMode}`}
          aria-pressed={repeatMode !== 'off'}
        >
          {repeatMode === 'one' ? <Repeat1 className="h-4 w-4" /> : <Repeat className="h-4 w-4" />}
        </button>
      )}
    </div>
  );
}
