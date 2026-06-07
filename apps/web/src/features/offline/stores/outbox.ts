import { type OutboxEntry } from '@yay-tsa/platform';

export interface CollapsedOutbox {
  // The latest entry per (kind + target) — the only ones worth replaying.
  keep: OutboxEntry[];
  // Ids of superseded duplicates that can be deleted before replay.
  staleIds: number[];
}

function dedupKey(entry: OutboxEntry): string {
  const target = entry.payload.itemId ?? entry.payload.trackId ?? '';
  return `${entry.kind}:${String(target)}`;
}

// Collapse the outbox so only the final state per target is replayed: the last
// favorite toggle for an item and the latest resume position for a track win;
// older duplicates are returned as stale ids to drop. Ordering by createdAt makes
// replay deterministic regardless of IndexedDB iteration order.
export function collapseOutbox(entries: OutboxEntry[]): CollapsedOutbox {
  const sorted = [...entries].sort((a, b) => a.createdAt - b.createdAt);
  const latest = new Map<string, OutboxEntry>();
  const staleIds: number[] = [];

  for (const entry of sorted) {
    const key = dedupKey(entry);
    const prior = latest.get(key);
    if (prior?.id !== undefined) staleIds.push(prior.id);
    latest.set(key, entry);
  }

  return { keep: [...latest.values()], staleIds };
}
