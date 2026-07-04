import { useMemo, useState, type ReactNode } from 'react';
import {
  Play,
  RotateCcw,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  CircleDot,
  Circle,
} from 'lucide-react';
import { ticksToSeconds, type AudiobookEntry } from '@yay-tsa/core';
import {
  useAudiobooks,
  useAudiobookActions,
  type AudiobookBook,
} from '@/features/audiobooks/hooks/useAudiobooks';
import { usePlayerStore, useCurrentTrack } from '@/features/player/stores/player.store';
import { MediaCard } from '@/features/library/components/MediaCard';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { SearchButton } from '@/shared/ui/SearchButton';

function chapterCount(n: number): string {
  return n === 1 ? '1 chapter' : `${n} chapters`;
}

function formatDuration(runTimeTicks: number | undefined): string {
  if (!runTimeTicks || runTimeTicks <= 0) return '';
  const total = Math.round(ticksToSeconds(runTimeTicks));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return h > 0 ? `${h}:${mm}:${String(s).padStart(2, '0')}` : `${mm}:${String(s).padStart(2, '0')}`;
}

function chapterStatusIcon(finished: boolean, started: boolean) {
  if (finished) return <CheckCircle2 size={16} className="text-accent" />;
  if (started) return <CircleDot size={16} className="text-accent" />;
  return <Circle size={16} className="opacity-40" />;
}

function ChapterRow({
  entry,
  index,
  isActive,
  onPlay,
}: Readonly<{
  entry: AudiobookEntry;
  index: number;
  isActive: boolean;
  onPlay: (index: number) => void;
}>) {
  const status = entry.resume.status;
  const finished = status === 'finished';
  const started =
    !finished &&
    (status === 'in_progress' || status === 'relistening') &&
    entry.resume.positionMs > 0;

  return (
    <button
      type="button"
      data-testid="audiobook-chapter"
      data-active={isActive}
      aria-current={isActive ? 'true' : undefined}
      onClick={() => onPlay(index)}
      className={`flex w-full items-center gap-3 rounded-md px-3 py-2 text-left text-sm transition-colors ${
        isActive ? 'bg-accent/15 text-text-primary' : 'text-text-secondary hover:bg-bg-tertiary'
      }`}
    >
      <span className="flex w-5 shrink-0 justify-center">
        {chapterStatusIcon(finished, started)}
      </span>
      <span className="text-text-tertiary w-6 shrink-0 text-right tabular-nums">{index + 1}</span>
      <span className="min-w-0 flex-1 truncate">{entry.item.Name}</span>
      <span className="text-text-tertiary shrink-0 tabular-nums">
        {formatDuration(entry.item.RunTimeTicks)}
      </span>
    </button>
  );
}

function ChapterList({ book }: Readonly<{ book: AudiobookBook }>) {
  const [open, setOpen] = useState(false);
  const playTracks = usePlayerStore(state => state.playTracks);
  const currentTrack = useCurrentTrack();

  const playChapter = (index: number) =>
    void playTracks(
      book.chapters.map(c => c.item),
      index
    );

  return (
    <div className="mt-3">
      <button
        type="button"
        data-testid="audiobook-chapters-toggle"
        onClick={() => setOpen(o => !o)}
        className="text-text-secondary hover:text-text-primary inline-flex items-center gap-1 text-xs font-medium"
      >
        {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        {open ? 'Hide chapters' : `Chapters (${book.totalChapters})`}
      </button>
      {open && (
        <div
          className="mt-2 max-h-80 space-y-0.5 overflow-y-auto pr-1"
          data-testid="audiobook-chapter-list"
        >
          {book.chapters.map((chapter, index) => (
            <ChapterRow
              key={chapter.item.Id}
              entry={chapter}
              index={index}
              isActive={currentTrack?.Id === chapter.item.Id}
              onPlay={playChapter}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function lastListened(updatedAt: string): string {
  const days = Math.floor((Date.now() - Date.parse(updatedAt)) / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  return `${days} days ago`;
}

function BookCard({ book }: Readonly<{ book: AudiobookBook }>) {
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

  let primaryLabel = 'Resume';
  if (isFinished) primaryLabel = 'Listen again';
  else if (isNotStarted) primaryLabel = 'Start';

  return (
    <div
      data-testid="audiobook-card"
      data-status={book.status}
      className="bg-bg-secondary flex gap-4 rounded-lg p-4"
    >
      <div className="w-20 shrink-0">
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
            className="bg-accent text-text-on-accent inline-flex items-center gap-1 rounded-full px-4 py-1.5 text-sm font-medium hover:opacity-90"
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

        {book.totalChapters > 1 && <ChapterList book={book} />}
      </div>
    </div>
  );
}

export function AudiobooksPage() {
  const { grouped, isLoading, error, refetch } = useAudiobooks();
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
        No audiobooks in your library yet. Tag tracks with the genre &ldquo;Audiobook&rdquo; to see
        them here.
      </p>
    );
  }

  return (
    <div className="space-y-8 p-6" data-testid="audiobooks-page">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-text-primary text-2xl font-bold">Audiobooks</h1>
        <SearchButton />
      </div>

      {error && (
        <div className="bg-error/10 text-error flex items-center justify-between gap-3 rounded-md p-4 text-sm">
          <span>Failed to load audiobooks.</span>
          <button
            type="button"
            onClick={() => {
              void refetch();
            }}
            className="bg-bg-secondary text-text-primary hover:bg-bg-tertiary shrink-0 rounded-md px-3 py-1.5 transition-colors"
          >
            Retry
          </button>
        </div>
      )}

      {body}
    </div>
  );
}
