import { type UserItemData } from '../internal/models/types.js';

/**
 * Single source of truth for favorite (heart) state in the Jellyfin wire model.
 *
 * Favorite is the ONLY persisted reaction in the system: a track/album/artist either is or isn't in
 * the user's favorites, exposed as `UserData.IsFavorite` and toggled via POST/DELETE
 * `/UserFavoriteItems/{id}`. Thumbs-up/down are ephemeral adaptive-session signals, not persisted
 * state, and intentionally have no filled/stored representation — they are not handled here.
 *
 * Every part of the UI reads favorite state through {@link getIsFavorite} and every optimistic patch
 * writes it through {@link setItemFavorite}, so there is exactly one reader and one writer shape.
 */
export type FavoritableItem = { Id?: string; UserData?: UserItemData };

const EMPTY_USER_DATA: UserItemData = {
  PlaybackPositionTicks: 0,
  PlayCount: 0,
  IsFavorite: false,
  Played: false,
};

/** The canonical reader. Use this everywhere instead of `item.UserData?.IsFavorite ?? false`. */
export function getIsFavorite(item: FavoritableItem | null | undefined): boolean {
  return item?.UserData?.IsFavorite ?? false;
}

/**
 * Returns a shallow copy of `item` with `UserData.IsFavorite` set, preserving any other UserData
 * fields. Identity is replaced (never mutated) so React reference-equality checks re-render.
 */
export function setItemFavorite<T extends FavoritableItem>(item: T, isFavorite: boolean): T {
  return {
    ...item,
    UserData: { ...(item.UserData ?? EMPTY_USER_DATA), IsFavorite: isFavorite },
  };
}
