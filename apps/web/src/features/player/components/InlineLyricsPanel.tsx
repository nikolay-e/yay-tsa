import { Loader2, Search } from 'lucide-react';
import { useLyrics } from '../hooks/useLyrics';
import { useLyricsFetch } from '../hooks/useLyricsFetch';
import { LyricsScroller } from './LyricsScroller';

export function InlineLyricsPanel() {
  const { parsedLyrics, activeLineIndex, isTimeSynced, hasLyrics } = useLyrics();
  const { isFetching, fetchError, handleFetch } = useLyricsFetch();

  if (hasLyrics && parsedLyrics) {
    return (
      <div className="h-full w-full">
        {!isTimeSynced && (
          <p className="text-text-tertiary mb-1 text-center text-xs">Not time-synced</p>
        )}
        <LyricsScroller
          lines={parsedLyrics.lines}
          activeLineIndex={activeLineIndex}
          isTimeSynced={isTimeSynced}
        />
      </div>
    );
  }

  return (
    <div className="flex h-full w-full flex-col items-center justify-center gap-4">
      {isFetching ? (
        <>
          <Loader2 className="text-accent h-8 w-8 animate-spin" />
          <span className="text-text-secondary text-sm">Searching...</span>
        </>
      ) : fetchError === 'not_found' ? (
        <span className="text-text-tertiary text-sm">No lyrics found</span>
      ) : fetchError ? (
        <div className="flex flex-col items-center gap-3">
          <span className="text-text-tertiary text-sm">{fetchError}</span>
          <button
            type="button"
            onClick={() => {
              void handleFetch();
            }}
            className="bg-bg-secondary hover:bg-bg-tertiary text-text-primary flex items-center gap-2 rounded-full px-5 py-2 text-sm transition-colors"
          >
            <Search className="h-4 w-4" /> Try Again
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
          <Search className="h-4 w-4" /> Search Lyrics
        </button>
      )}
    </div>
  );
}
