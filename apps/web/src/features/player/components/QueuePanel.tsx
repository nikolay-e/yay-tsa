import { useMemo } from 'react';
import { X, ListMusic } from 'lucide-react';
import type { AudioItem } from '@yay-tsa/core';
import { Modal } from '@/shared/ui/Modal';
import { SortableList } from '@/shared/ui/SortableList';
import { toast } from '@/shared/ui/Toast';
import { cn } from '@/shared/utils/cn';
import { formatTicks } from '@/shared/utils/time';
import { usePlayerStore, useQueueItems, useQueueIndex } from '../stores/player.store';

// The queue may contain the same track twice, so each row gets a synthetic unique
// Id (trackId:position) for dnd-kit while keeping its real queue position.
type QueueEntry = { Id: string; track: AudioItem; queueIndex: number };

// SortableList reports a full reordered array produced by a single arrayMove, so the
// moved element is recoverable from the first and last out-of-place positions.
function singleMoveFromReorder(
  reordered: QueueEntry[]
): { fromIndex: number; toIndex: number } | null {
  const mismatches = reordered
    .map((entry, position) => ({ entry, position }))
    .filter(({ entry, position }) => entry.queueIndex !== position);
  const first = mismatches[0];
  const last = mismatches[mismatches.length - 1];
  if (!first || !last) return null;
  if (first.entry.queueIndex === last.position) {
    return { fromIndex: last.position, toIndex: first.position };
  }
  return { fromIndex: first.position, toIndex: last.position };
}

export default function QueuePanel({
  isOpen,
  onClose,
}: Readonly<{ isOpen: boolean; onClose: () => void }>) {
  const queueItems = useQueueItems();
  const queueIndex = useQueueIndex();
  const jumpToQueueTrack = usePlayerStore(s => s.jumpToQueueTrack);
  const removeFromQueue = usePlayerStore(s => s.removeFromQueue);
  const moveQueueItem = usePlayerStore(s => s.moveQueueItem);

  const entries = useMemo<QueueEntry[]>(
    () =>
      queueItems.map((track, index) => ({ Id: `${track.Id}:${index}`, track, queueIndex: index })),
    [queueItems]
  );

  const handleReorder = (reordered: QueueEntry[]) => {
    const move = singleMoveFromReorder(reordered);
    if (move) moveQueueItem(move.fromIndex, move.toIndex);
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      ariaLabelledBy="queue-panel-title"
      backdropClassName="bg-bg-primary/80 flex items-end justify-center backdrop-blur-sm md:items-center"
      className="bg-bg-secondary border-border flex max-h-[70vh] w-full max-w-md flex-col rounded-t-2xl border p-4 md:max-h-[80vh] md:rounded-2xl"
    >
      <div data-testid="queue-panel" className="flex min-h-0 flex-col">
        <div className="mb-3 flex items-center justify-between">
          <h2
            id="queue-panel-title"
            className="text-text-primary flex items-center gap-2 text-lg font-semibold"
          >
            <ListMusic className="h-5 w-5" />
            Queue
            <span className="text-text-tertiary text-sm font-normal">
              {queueItems.length} {queueItems.length === 1 ? 'track' : 'tracks'}
            </span>
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary flex min-h-11 min-w-11 items-center justify-center rounded-full transition-colors"
            aria-label="Close queue"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {queueItems.length === 0 ? (
          <p className="text-text-tertiary py-8 text-center text-sm">Queue is empty</p>
        ) : (
          <div className="min-h-0 overflow-y-auto">
            <SortableList
              items={entries}
              onReorder={handleReorder}
              renderItem={({ track, queueIndex: index }) => {
                const isCurrent = index === queueIndex;
                return (
                  <div
                    data-testid="queue-item"
                    aria-current={isCurrent ? 'true' : undefined}
                    className={cn(
                      'flex items-center gap-2 rounded-lg px-2 py-1',
                      isCurrent ? 'bg-accent/10' : 'hover:bg-bg-tertiary'
                    )}
                  >
                    <button
                      type="button"
                      onClick={() => {
                        void jumpToQueueTrack(track.Id);
                      }}
                      className="focus-visible:ring-accent flex min-h-11 min-w-0 flex-1 cursor-pointer items-center gap-3 rounded text-left focus-visible:ring-2 focus-visible:outline-none"
                      aria-label={`Play ${track.Name}`}
                    >
                      <span
                        className={cn(
                          'w-5 shrink-0 text-right text-xs tabular-nums',
                          isCurrent ? 'text-accent' : 'text-text-tertiary'
                        )}
                      >
                        {index + 1}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span
                          data-testid="queue-item-title"
                          className={cn(
                            'block truncate text-sm font-medium',
                            isCurrent ? 'text-accent' : 'text-text-primary'
                          )}
                        >
                          {track.Name}
                        </span>
                        <span className="text-text-secondary block truncate text-xs">
                          {track.Artists?.[0] ?? 'Unknown Artist'}
                        </span>
                      </span>
                    </button>
                    <span className="text-text-tertiary shrink-0 text-xs tabular-nums">
                      {formatTicks(track.RunTimeTicks)}
                    </span>
                    {!isCurrent && (
                      <button
                        type="button"
                        data-testid="queue-item-remove"
                        onClick={() => {
                          removeFromQueue(track.Id);
                          toast.add('info', `Removed ${track.Name} from queue`);
                        }}
                        className="text-text-tertiary hover:text-error flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-full transition-colors"
                        aria-label={`Remove ${track.Name} from queue`}
                      >
                        <X className="h-4 w-4" />
                      </button>
                    )}
                  </div>
                );
              }}
            />
          </div>
        )}
      </div>
    </Modal>
  );
}
