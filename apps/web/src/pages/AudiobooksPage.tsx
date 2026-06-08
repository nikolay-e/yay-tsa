import { useMemo } from 'react';
import { Play, RotateCcw, CheckCircle2 } from 'lucide-react';
import type { AudiobookEntry } from '@yay-tsa/core';
import { useAudiobooks, useAudiobookActions } from '@/features/audiobooks/hooks/useAudiobooks';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { MediaCard } from '@/features/library/components/MediaCard';
import { LoadingSpinner } from '@/shared/ui/LoadingSpinner';
import { formatSeconds } from '@/shared/utils/time';

function formatRemaining(ms: number): string {
  const totalMinutes = Math.max(0, Math.floor(ms / 60000));
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours > 0) return `${hours}h ${minutes}m left`;
  return `${minutes}m left`;
}

function lastListened(updatedAt: string): string {
  const days = Math.floor((Date.now() - Date.parse(updatedAt)) / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  return `${days} days ago`;
}

function AudiobookCard({
  entry,
  hero = false,
}: Readonly<{ entry: AudiobookEntry; hero?: boolean }>) {
  const { item, resume } = entry;
  const playTrack = usePlayerStore(state => state.playTrack);
  const { markFinished, restart } = useAudiobookActions();
  const isFinished = resume.status === 'finished';

  return (
    <div
      data-testid={hero ? 'audiobook-continue' : 'audiobook-card'}
      data-status={resume.status}
      className="bg-bg-secondary flex gap-4 rounded-lg p-4"
    >
      <div className={hero ? 'w-32 shrink-0' : 'w-20 shrink-0'}>
        <MediaCard
          itemId={item.Id}
          imageTag={item.AlbumPrimaryImageTag ?? item.ImageTags?.Primary}
          imageAlt={item.Name}
          imageShape="square"
        >
          <span className="sr-only">{item.Name}</span>
        </MediaCard>
      </div>

      <div className="flex min-w-0 flex-1 flex-col justify-between">
        <div className="min-w-0">
          <h3 className="text-text-primary truncate font-semibold">{item.Name}</h3>
          {item.Artists && item.Artists.length > 0 && (
            <p className="text-text-secondary truncate text-sm">{item.Artists.join(', ')}</p>
          )}
          {!isFinished && (
            <p className="text-text-secondary mt-1 text-xs">
              {resume.progressPercent}% · {formatSeconds(resume.positionMs / 1000)} /{' '}
              {formatSeconds(resume.runTimeMs / 1000)} · {formatRemaining(resume.remainingMs)}
            </p>
          )}
          <p className="text-text-secondary text-xs">
            {isFinished ? 'Finished' : `Last listened ${lastListened(resume.updatedAt)}`}
          </p>
        </div>

        {!isFinished && (
          <div className="bg-bg-tertiary mt-2 h-1 w-full overflow-hidden rounded-full">
            <div
              className="bg-accent h-full rounded-full"
              style={{ width: `${Math.min(100, resume.progressPercent)}%` }}
            />
          </div>
        )}

        <div className="mt-3 flex flex-wrap gap-2">
          <button
            type="button"
            data-testid="audiobook-resume"
            onClick={() => void playTrack(item)}
            className="bg-accent inline-flex items-center gap-1 rounded-full px-4 py-1.5 text-sm font-medium text-white hover:opacity-90"
          >
            <Play size={16} /> {isFinished ? 'Listen again' : 'Resume'}
          </button>
          {isFinished ? (
            <button
              type="button"
              data-testid="audiobook-restart"
              onClick={() => restart.mutate(item.Id)}
              className="bg-bg-tertiary text-text-primary hover:bg-bg-hover inline-flex items-center gap-1 rounded-full px-4 py-1.5 text-sm"
            >
              <RotateCcw size={16} /> Restart
            </button>
          ) : (
            <button
              type="button"
              data-testid="audiobook-finish"
              onClick={() => markFinished.mutate(item.Id)}
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
    () => grouped.inProgress.length > 0 || grouped.finished.length > 0,
    [grouped]
  );

  return (
    <div className="space-y-8 p-6" data-testid="audiobooks-page">
      <h1 className="text-text-primary text-2xl font-bold">Audiobooks</h1>

      {error && (
        <div className="rounded-md bg-red-500/10 p-4 text-sm text-red-400">
          Failed to load audiobooks.
        </div>
      )}

      {isLoading ? (
        <LoadingSpinner />
      ) : !hasAny ? (
        <p data-testid="audiobooks-empty" className="text-text-secondary">
          No audiobooks in progress yet. Play an audiobook to see it here.
        </p>
      ) : (
        <>
          {grouped.continueListening && (
            <section className="space-y-3">
              <h2 className="text-text-primary text-lg font-semibold">Continue Listening</h2>
              <AudiobookCard entry={grouped.continueListening} hero />
            </section>
          )}

          {grouped.inProgress.length > 0 && (
            <section className="space-y-3">
              <h2 className="text-text-primary text-lg font-semibold">In Progress</h2>
              <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                {grouped.inProgress.map(entry => (
                  <AudiobookCard key={entry.item.Id} entry={entry} />
                ))}
              </div>
            </section>
          )}

          {grouped.finished.length > 0 && (
            <section className="space-y-3">
              <h2 className="text-text-primary text-lg font-semibold">Finished</h2>
              <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                {grouped.finished.map(entry => (
                  <AudiobookCard key={entry.item.Id} entry={entry} />
                ))}
              </div>
            </section>
          )}
        </>
      )}
    </div>
  );
}
