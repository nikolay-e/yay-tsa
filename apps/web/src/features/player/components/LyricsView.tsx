import { useCallback } from 'react';
import { X, Search, Loader2, Play, Pause, SkipBack, SkipForward } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { Modal } from '@/shared/ui/Modal';
import { useLyrics } from '../hooks/useLyrics';
import { useLyricsFetch } from '../hooks/useLyricsFetch';
import { useCurrentTrack, useIsPlaying, usePlayerStore } from '../stores/player.store';
import { LyricsScroller } from './LyricsScroller';
import { SeekBar } from './SeekBar';

type LyricsViewProps = Readonly<{
  isOpen: boolean;
  onClose: () => void;
}>;

export function LyricsView({ isOpen, onClose }: LyricsViewProps) {
  const currentTrack = useCurrentTrack();
  const isPlaying = useIsPlaying();
  const { parsedLyrics, activeLineIndex, isTimeSynced, hasLyrics } = useLyrics();
  const { isFetching, fetchError, handleFetch } = useLyricsFetch();

  const handlePlayPause = useCallback(() => {
    const store = usePlayerStore.getState();
    if (store.isPlaying) store.pause();
    else void store.resume();
  }, []);
  const handleNext = useCallback(() => void usePlayerStore.getState().next(), []);
  const handlePrevious = useCallback(() => void usePlayerStore.getState().previous(), []);
  const handleSeek = useCallback((seconds: number) => usePlayerStore.getState().seek(seconds), []);

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      ariaLabelledBy="lyrics-dialog-title"
      backdropClassName="bg-black/80 md:flex md:items-center md:justify-center"
      className={cn(
        'bg-bg-primary pt-safe px-safe flex h-full flex-col md:h-auto md:max-h-[80vh] md:w-full md:max-w-2xl md:rounded-lg md:px-0 md:pt-0 md:pb-0'
      )}
    >
      <div className="border-border flex shrink-0 items-center justify-between border-b p-4">
        <div className="min-w-0 flex-1">
          <h2 id="lyrics-dialog-title" className="text-text-primary truncate text-lg font-semibold">
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

      <div
        className="border-border shrink-0 border-t px-4 pt-3"
        style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.75rem)' }}
      >
        <SeekBar onSeek={handleSeek} />
        <div className="mt-2 flex items-center justify-center gap-6">
          <button
            type="button"
            onClick={handlePrevious}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Previous"
          >
            <SkipBack className="h-5 w-5" fill="currentColor" />
          </button>
          <button
            type="button"
            onClick={handlePlayPause}
            className="bg-accent text-text-on-accent hover:bg-accent-hover focus-visible:ring-accent rounded-full p-3 transition-colors focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none"
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
            onClick={handleNext}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Next"
          >
            <SkipForward className="h-5 w-5" fill="currentColor" />
          </button>
        </div>
      </div>
    </Modal>
  );
}
