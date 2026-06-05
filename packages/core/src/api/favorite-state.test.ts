import { describe, it, expect } from 'vitest';
import { getIsFavorite, setItemFavorite } from './favorite-state.js';
import type { AudioItem } from '../internal/models/types.js';

function track(fav?: boolean): AudioItem {
  return {
    Id: 't1',
    Name: 'Track',
    Type: 'Audio',
    RunTimeTicks: 1,
    ...(fav === undefined
      ? {}
      : { UserData: { PlaybackPositionTicks: 5, PlayCount: 3, IsFavorite: fav, Played: true } }),
  } as AudioItem;
}

describe('getIsFavorite', () => {
  it('reads a true flag', () => {
    expect(getIsFavorite(track(true))).toBe(true);
  });

  it('reads a false flag', () => {
    expect(getIsFavorite(track(false))).toBe(false);
  });

  it('defaults to false when UserData is absent', () => {
    expect(getIsFavorite(track())).toBe(false);
  });

  it('defaults to false for null/undefined items', () => {
    expect(getIsFavorite(null)).toBe(false);
    expect(getIsFavorite(undefined)).toBe(false);
  });
});

describe('setItemFavorite', () => {
  it('sets the flag while preserving the rest of UserData', () => {
    const next = setItemFavorite(track(false), true);
    expect(next.UserData?.IsFavorite).toBe(true);
    expect(next.UserData?.PlayCount).toBe(3);
    expect(next.UserData?.PlaybackPositionTicks).toBe(5);
  });

  it('does not mutate the original item', () => {
    const original = track(false);
    const next = setItemFavorite(original, true);
    expect(original.UserData?.IsFavorite).toBe(false);
    expect(next).not.toBe(original);
    expect(next.UserData).not.toBe(original.UserData);
  });

  it('synthesises UserData when the item has none', () => {
    const next = setItemFavorite(track(), true);
    expect(next.UserData).toEqual({
      PlaybackPositionTicks: 0,
      PlayCount: 0,
      IsFavorite: true,
      Played: false,
    });
  });
});
