import { describe, it, expect } from 'vitest';
import { type OutboxEntry } from '@yay-tsa/platform';
import { collapseOutbox } from './outbox';

function fav(id: number, itemId: string, makeFavorite: boolean, createdAt: number): OutboxEntry {
  return { id, kind: 'favorite', createdAt, payload: { itemId, makeFavorite } };
}

function prog(id: number, trackId: string, positionTicks: number, createdAt: number): OutboxEntry {
  return { id, kind: 'progress', createdAt, payload: { trackId, positionTicks } };
}

describe('collapseOutbox', () => {
  it('returns nothing for an empty outbox', () => {
    expect(collapseOutbox([])).toEqual({ keep: [], staleIds: [] });
  });

  it('keeps a single entry untouched', () => {
    const entry = fav(1, 'a', true, 100);
    const result = collapseOutbox([entry]);
    expect(result.keep).toEqual([entry]);
    expect(result.staleIds).toEqual([]);
  });

  it('collapses repeated favorite toggles for the same item to the latest', () => {
    const entries = [fav(1, 'a', true, 100), fav(2, 'a', false, 200), fav(3, 'a', true, 300)];
    const result = collapseOutbox(entries);
    expect(result.keep).toHaveLength(1);
    expect(result.keep[0]?.id).toBe(3);
    expect(result.keep[0]?.payload.makeFavorite).toBe(true);
    expect(result.staleIds.sort()).toEqual([1, 2]);
  });

  it('keeps the latest by createdAt regardless of array order', () => {
    const entries = [fav(3, 'a', true, 300), fav(1, 'a', false, 100), fav(2, 'a', true, 200)];
    const result = collapseOutbox(entries);
    expect(result.keep[0]?.id).toBe(3);
    expect(result.staleIds.sort()).toEqual([1, 2]);
  });

  it('treats different items and kinds as independent targets', () => {
    const entries = [
      fav(1, 'a', true, 100),
      fav(2, 'b', true, 150),
      prog(3, 'a', 10_000, 200),
      prog(4, 'a', 20_000, 300),
    ];
    const result = collapseOutbox(entries);
    // favorite:a, favorite:b, progress:a  → 3 kept; one stale progress (id 3)
    expect(result.keep).toHaveLength(3);
    expect(result.staleIds).toEqual([3]);
    const progressKept = result.keep.find(e => e.kind === 'progress');
    expect(progressKept?.payload.positionTicks).toBe(20_000);
  });
});
