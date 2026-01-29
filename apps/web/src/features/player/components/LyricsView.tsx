import { useEffect, useRef, useCallback } from 'react';
import { X } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { useFocusTrap } from '@/shared/hooks/useFocusTrap';
import { useLyrics } from '../hooks/useLyrics';
import { useCurrentTrack } from '../stores/player.store';
import { LyricLine } from './LyricLine';

interface LyricsViewProps {
  onClose: () => void;
}

export function LyricsView({ onClose }: LyricsViewProps) {
  const currentTrack = useCurrentTrack();
  const { parsedLyrics, activeLineIndex, isTimeSynced, hasLyrics } = useLyrics();
  const activeLineRef = useRef<HTMLDivElement>(null);
  const lastScrolledIndex = useRef<number>(-1);
  const dialogRef = useFocusTrap<HTMLDivElement>(true);

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    },
    [onClose]
  );

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown, { capture: true });

    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', handleKeyDown, { capture: true });
      document.body.style.overflow = originalOverflow;
    };
  }, [handleKeyDown]);

  useEffect(() => {
    if (!isTimeSynced || activeLineIndex < 0) return;
    if (activeLineIndex === lastScrolledIndex.current) return;

    lastScrolledIndex.current = activeLineIndex;

    const timeoutId = setTimeout(() => {
      try {
        activeLineRef.current?.scrollIntoView({
          behavior: 'smooth',
          block: 'center',
        });
      } catch {
        // Element may be detached during unmount
      }
    }, 100);

    return () => clearTimeout(timeoutId);
  }, [activeLineIndex, isTimeSynced]);

  const getLineState = (index: number): 'active' | 'past' | 'future' => {
    if (!isTimeSynced) return 'future';
    if (index === activeLineIndex) return 'active';
    if (index < activeLineIndex) return 'past';
    return 'future';
  };

  return (
    <div
      role="presentation"
      className="z-modal-backdrop fixed inset-0 bg-black/80 md:flex md:items-center md:justify-center"
      onClick={onClose}
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="lyrics-dialog-title"
        className={cn(
          'bg-bg-primary flex h-full flex-col md:h-auto md:max-h-[80vh] md:w-full md:max-w-2xl md:rounded-lg'
        )}
        onClick={e => e.stopPropagation()}
        onKeyDown={e => e.stopPropagation()}
      >
        <div className="border-border flex shrink-0 items-center justify-between border-b p-4">
          <div className="min-w-0 flex-1">
            <h2
              id="lyrics-dialog-title"
              className="text-text-primary truncate text-lg font-semibold"
            >
              {currentTrack?.Name ?? 'Lyrics'}
            </h2>
            {currentTrack?.Artists?.[0] && (
              <p className="text-text-secondary truncate text-sm">{currentTrack.Artists[0]}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent ml-4 rounded-full p-2 focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Close lyrics"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto py-8">
          {!hasLyrics ? (
            <div className="text-text-tertiary flex h-full items-center justify-center">
              No lyrics available
            </div>
          ) : (
            <>
              {!isTimeSynced && (
                <div className="text-text-tertiary mb-4 text-center text-sm">
                  Lyrics are not time-synced
                </div>
              )}
              <div className="space-y-1">
                {parsedLyrics?.lines.map((line, index) => (
                  <LyricLine
                    key={`${index}-${line.time}`}
                    ref={index === activeLineIndex ? activeLineRef : undefined}
                    text={line.text}
                    state={getLineState(index)}
                  />
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
