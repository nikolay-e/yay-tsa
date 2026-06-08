import { useMemo, type ReactNode } from 'react';
import { Play, RotateCcw, CheckCircle2 } from 'lucide-react';
import {
  useAudiobooks,
  useAudiobookActions,
  type AudiobookBook,
} from '@/features/audiobooks/hooks/useAudiobooks';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { MediaCard } from '@/features/library/components/MediaCard';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';

function chapterCount(n: number): string {
  return n === 1 ? '1 chapter' : `${n} chapters`;
}

function lastListened(updatedAt: string): string {
  const days = Math.floor((Date.now() - Date.parse(updatedAt)) / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  return `${days} days ago`;
}

function BookCard({ book, hero = false }: Readonly<{ book: AudiobookBook; hero?: boolean }>) {
  const playTracks = usePlayerStore(state => state.playTracks);
  const { markFinished, restart } = useAudiobookActions();

  const chapterIds = book.chapters.map(c => c.item.Id);
  const play = () =>
    void playTracks(
      book.chapters.map(c => c.item),
      book.resumeChapterIndex
    );

  const isFinished = book.status === 'finished';
  const isNotStarted = book.status === 'not_started';
  const inProgress = book.status === 'in_progress';

  const primaryLabel = isFinished ? 'Listen again' : isNotStarted ? 'Start' : 'Resume';

  return (
    <div
      data-testid={hero ? 'audiobook-continue' : 'audiobook-card'}
      data-status={book.status}
      className="bg-bg-secondary flex gap-4 rounded-lg p-4"
    >
      <div className={hero ? 'w-32 shrink-0' : 'w-20 shrink-0'}>
        <MediaCard
          itemId={book.coverItemId}
          imageTag={book.coverImageTag}
          imageAlt={book.title}
          imageShape="square"
        >
          <span className="sr-only">{book.title}</span>
        </MediaCard>
      </div>

      <div className="flex min-w-0 flex-1 flex-col justify-between">
        <div className="min-w-0">
          <h3 className="text-text-primary truncate font-semibold">{book.title}</h3>
          {book.author && <p className="text-text-secondary truncate text-sm">{book.author}</p>}
          {inProgress && (
            <p className="text-text-secondary mt-1 text-xs">
              {book.progressPercent}% · Chapter {book.resumeChapterIndex + 1} of{' '}
              {book.totalChapters}
            </p>
          )}
          <p className="text-text-secondary text-xs">
            {isFinished && `${chapterCount(book.totalChapters)} · Finished`}
            {isNotStarted && `${chapterCount(book.totalChapters)} · Not started`}
            {inProgress && `Last listened ${lastListened(book.lastUpdatedAt)}`}
          </p>
        </div>

        {inProgress && (
          <div className="bg-bg-tertiary mt-2 h-1 w-full overflow-hidden rounded-full">
            <div
              className="bg-accent h-full rounded-full"
              style={{ width: `${Math.min(100, book.progressPercent)}%` }}
            />
          </div>
        )}

        <div className="mt-3 flex flex-wrap gap-2">
          <button
            type="button"
            data-testid="audiobook-resume"
            onClick={play}
            className="bg-accent inline-flex items-center gap-1 rounded-full px-4 py-1.5 text-sm font-medium text-white hover:opacity-90"
          >
            <Play size={16} /> {primaryLabel}
          </button>
          {isFinished && (
            <button
              type="button"
              data-testid="audiobook-restart"
              onClick={() => restart.mutate(chapterIds)}
              className="bg-bg-tertiary text-text-primary hover:bg-bg-hover inline-flex items-center gap-1 rounded-full px-4 py-1.5 text-sm"
            >
              <RotateCcw size={16} /> Restart
            </button>
          )}
          {inProgress && (
            <button
              type="button"
              data-testid="audiobook-finish"
              onClick={() => markFinished.mutate(chapterIds)}
              className="bg-bg-tertiary text-text-primary hover:bg-bg-hover inline-flex items-center gap-1 rounded-full px-4 py-1.5 text-sm"
            >
              <CheckCircle2 size={16} /> Mark finished
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

export function AudiobooksPage() {
  const { grouped, isLoading, error } = useAudiobooks();
  const hasAny = useMemo(
    () =>
      grouped.inProgress.length > 0 || grouped.finished.length > 0 || grouped.notStarted.length > 0,
    [grouped]
  );

  let body: ReactNode;
  if (isLoading) {
    body = <LoadingSpinner />;
  } else if (hasAny) {
    body = (
      <>
        {grouped.continueListening && (
          <section className="space-y-3">
            <h2 className="text-text-primary text-lg font-semibold">Continue Listening</h2>
            <BookCard book={grouped.continueListening} hero />
          </section>
        )}

        {grouped.inProgress.length > 0 && (
          <section className="space-y-3">
            <h2 className="text-text-primary text-lg font-semibold">In Progress</h2>
            <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
              {grouped.inProgress.map(book => (
                <BookCard key={book.albumId} book={book} />
              ))}
            </div>
          </section>
        )}

        {grouped.finished.length > 0 && (
          <section className="space-y-3">
            <h2 className="text-text-primary text-lg font-semibold">Finished</h2>
            <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
              {grouped.finished.map(book => (
                <BookCard key={book.albumId} book={book} />
              ))}
            </div>
          </section>
        )}

        {grouped.notStarted.length > 0 && (
          <section className="space-y-3">
            <h2 className="text-text-primary text-lg font-semibold">Library</h2>
            <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
              {grouped.notStarted.map(book => (
                <BookCard key={book.albumId} book={book} />
              ))}
            </div>
          </section>
        )}
      </>
    );
  } else {
    body = (
      <p data-testid="audiobooks-empty" className="text-text-secondary">
        No audiobooks in your library yet. Tag tracks with the genre "Audiobook" to see them here.
      </p>
    );
  }

  return (
    <div className="space-y-8 p-6" data-testid="audiobooks-page">
      <h1 className="text-text-primary text-2xl font-bold">Audiobooks</h1>

      {error && (
        <div className="rounded-md bg-red-500/10 p-4 text-sm text-red-400">
          Failed to load audiobooks.
        </div>
      )}

      {body}
    </div>
  );
}
