import { Mic2, Search, Loader2, X } from 'lucide-react';
import { Modal } from '@/shared/ui/Modal';
import { LYRICS_TEST_IDS } from '@/shared/testing/test-ids';
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

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      ariaLabelledBy="lyrics-panel-title"
      backdropClassName="bg-bg-primary/80 flex items-end justify-center backdrop-blur-sm md:items-center"
      className="bg-bg-secondary border-border flex h-[85vh] max-h-[85vh] w-full max-w-lg flex-col rounded-t-2xl border p-4 md:max-w-2xl md:rounded-2xl"
    >
      <div data-testid={LYRICS_TEST_IDS.OVERLAY} className="flex min-h-0 flex-1 flex-col">
        <div className="mb-3 flex items-center justify-between gap-4">
          <div className="flex min-w-0 items-center gap-2">
            <Mic2 className="text-text-secondary h-5 w-5 shrink-0" />
            <div className="min-w-0">
              <h2
                id="lyrics-panel-title"
                className="text-text-primary truncate text-lg font-semibold"
              >
                {currentTrack?.Name ?? 'Lyrics'}
              </h2>
              {currentTrack?.Artists?.[0] && (
                <p className="text-text-secondary truncate text-sm">{currentTrack.Artists[0]}</p>
              )}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-full transition-colors focus-visible:ring-2 focus-visible:outline-none"
            aria-label="Close lyrics"
            data-testid={LYRICS_TEST_IDS.CLOSE_BUTTON}
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-hidden" data-testid={LYRICS_TEST_IDS.CONTENT}>
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
                  <span
                    className="text-text-secondary text-sm"
                    data-testid={LYRICS_TEST_IDS.LOADING}
                  >
                    Searching for lyrics...
                  </span>
                </div>
              ) : (
                (() => {
                  const notFoundContent = (
                    <span
                      className="text-text-tertiary text-sm"
                      data-testid={LYRICS_TEST_IDS.NOT_FOUND}
                    >
                      No lyrics found
                    </span>
                  );
                  const errorContent = fetchError ? (
                    <div className="flex flex-col items-center gap-3">
                      <span className="text-text-tertiary text-sm">{fetchError}</span>
                      <button
                        type="button"
                        onClick={() => {
                          handleFetch();
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
                        handleFetch();
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
    </Modal>
  );
}
