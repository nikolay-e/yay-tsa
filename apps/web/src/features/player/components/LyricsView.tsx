import { useEffect, useRef, useCallback, useState } from 'react';
import { X, Search, Loader2 } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { useFocusTrap } from '@/shared/hooks/useFocusTrap';
import { log } from '@/shared/utils/logger';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useLyrics } from '../hooks/useLyrics';
import { useCurrentTrack, usePlayerStore } from '../stores/player.store';
import { LyricLine } from './LyricLine';

interface LyricsViewProps {
  onClose: () => void;
}

export function LyricsView({ onClose }: LyricsViewProps) {
  const currentTrack = useCurrentTrack();
  const client = useAuthStore(state => state.client);
  const { parsedLyrics, activeLineIndex, isTimeSynced, hasLyrics } = useLyrics();
  const [isFetching, setIsFetching] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
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

  useEffect(() => {
    setIsFetching(false);
    setFetchError(null);
  }, [currentTrack?.Id]);

  const handleFetchLyrics = async () => {
    if (!client || !currentTrack) return;
    const trackId = currentTrack.Id;
    setIsFetching(true);
    setFetchError(null);
    try {
      const result = await client.fetchLyrics(trackId);
      if (usePlayerStore.getState().currentTrack?.Id !== trackId) return;
      if (result.found && result.lyrics) {
        usePlayerStore.getState().updateCurrentTrackLyrics(result.lyrics);
      } else {
        setFetchError('not_found');
      }
    } catch (err) {
      log.player.error('fetchLyrics error', err);
      if (usePlayerStore.getState().currentTrack?.Id !== trackId) return;
      setFetchError('Lyrics service unavailable');
    } finally {
      if (usePlayerStore.getState().currentTrack?.Id === trackId) {
        setIsFetching(false);
      }
    }
  };

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
          'bg-bg-primary pt-safe pb-safe px-safe flex h-full flex-col md:h-auto md:max-h-[80vh] md:w-full md:max-w-2xl md:rounded-lg md:px-0 md:pt-0 md:pb-0'
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
            <div className="flex h-full flex-col items-center justify-center gap-4">
              {isFetching ? (
                <div className="flex flex-col items-center gap-3">
                  <Loader2 className="text-accent h-8 w-8 animate-spin" />
                  <span className="text-text-secondary text-sm">Searching for lyrics...</span>
                </div>
              ) : fetchError === 'not_found' ? (
                <span className="text-text-tertiary text-sm">No lyrics found</span>
              ) : fetchError ? (
                <div className="flex flex-col items-center gap-3">
                  <span className="text-text-tertiary text-sm">{fetchError}</span>
                  <button
                    type="button"
                    onClick={() => void handleFetchLyrics()}
                    className="bg-bg-secondary hover:bg-bg-tertiary text-text-primary flex items-center gap-2 rounded-full px-5 py-2 text-sm transition-colors"
                  >
                    <Search className="h-4 w-4" />
                    Try Again
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => void handleFetchLyrics()}
                  className="bg-accent hover:bg-accent-hover flex items-center gap-2 rounded-full px-6 py-2.5 text-black transition-colors"
                >
                  <Search className="h-4 w-4" />
                  Search Lyrics
                </button>
              )}
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
