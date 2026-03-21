import { useEffect } from 'react';
import { X, Search, Loader2 } from 'lucide-react';
import { useLyrics } from '../hooks/useLyrics';
import { useLyricsFetch } from '../hooks/useLyricsFetch';
import { useCurrentTrack } from '../stores/player.store';
import { LyricsScroller } from './LyricsScroller';

type LyricsViewProps = Readonly<{
  isOpen: boolean;
  onClose: () => void;
}>;

export function LyricsView({ isOpen, onClose }: LyricsViewProps) {
  const currentTrack = useCurrentTrack();
  const { parsedLyrics, activeLineIndex, isTimeSynced, hasLyrics } = useLyrics();
  const { isFetching, fetchError, handleFetch } = useLyricsFetch();

  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div
      className="bg-bg-primary md:left-sidebar fixed inset-0 z-[95] flex flex-col"
      style={{ bottom: 'var(--spacing-player-bar, 76px)' }}
    >
      <div className="border-border flex shrink-0 items-center justify-between border-b p-4">
        <div className="min-w-0 flex-1">
          <h2 className="text-text-primary truncate text-lg font-semibold">
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

      <div className="flex-1 overflow-hidden">
        {hasLyrics && parsedLyrics ? (
          <div className="h-full">
            {!isTimeSynced && (
              <div className="text-text-tertiary pt-2 text-center text-sm">
                Lyrics are not time-synced
              </div>
            )}
            <LyricsScroller
              lines={parsedLyrics.lines}
              activeLineIndex={activeLineIndex}
              isTimeSynced={isTimeSynced}
            />
          </div>
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-4">
            {isFetching ? (
              <div className="flex flex-col items-center gap-3">
                <Loader2 className="text-accent h-8 w-8 animate-spin" />
                <span className="text-text-secondary text-sm">Searching for lyrics...</span>
              </div>
            ) : (
              (() => {
                const notFoundContent = (
                  <span className="text-text-tertiary text-sm">No lyrics found</span>
                );
                const errorContent = fetchError ? (
                  <div className="flex flex-col items-center gap-3">
                    <span className="text-text-tertiary text-sm">{fetchError}</span>
                    <button
                      type="button"
                      onClick={() => {
                        void handleFetch();
                      }}
                      className="bg-bg-secondary hover:bg-bg-tertiary text-text-primary flex items-center gap-2 rounded-full px-5 py-2 text-sm transition-colors"
                    >
                      <Search className="h-4 w-4" />
                      Try Again
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={() => {
                      void handleFetch();
                    }}
                    className="bg-accent hover:bg-accent-hover text-text-on-accent flex items-center gap-2 rounded-full px-6 py-2.5 transition-colors"
                  >
                    <Search className="h-4 w-4" />
                    Search Lyrics
                  </button>
                );
                return fetchError === 'not_found' ? notFoundContent : errorContent;
              })()
            )}
          </div>
        )}
      </div>
    </div>
  );
}
