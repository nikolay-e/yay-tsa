import { useMutation, useQueryClient } from '@tanstack/react-query';
import { create } from 'zustand';
import { FavoritesService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { usePlayerStore } from '@/features/player/stores/player.store';
import { useOfflineStore } from '@/features/offline/stores/offline.store';

interface FavoriteToggleParams {
  itemId: string;
  isFavorite: boolean;
}

// Shared in-flight lock, keyed by itemId. Item ids are globally unique (UUIDs), so the id alone
// identifies the favoritable entity regardless of type, and a single lock per id is correct. Every
// FavoriteButton for the same item subscribes to this, so the heart disables in lockstep across
// Songs / Now Playing / Queue / Favorites / detail pages while a toggle is in flight — there is no
// per-instance pending state that could let two concurrent mutations race. The lock is acquired
// synchronously at click time (see FavoriteButton) and released on settle, so a second click in the
// same frame is rejected before any network call.
interface FavoritePendingState {
  pending: ReadonlySet<string>;
  begin: (itemId: string) => void;
  end: (itemId: string) => void;
}

export const useFavoritePendingStore = create<FavoritePendingState>(set => ({
  pending: new Set<string>(),
  begin: itemId =>
    set(state => {
      if (state.pending.has(itemId)) return state;
      const next = new Set(state.pending);
      next.add(itemId);
      return { pending: next };
    }),
  end: itemId =>
    set(state => {
      if (!state.pending.has(itemId)) return state;
      const next = new Set(state.pending);
      next.delete(itemId);
      return { pending: next };
    }),
}));

export const useIsFavoritePending = (itemId: string): boolean =>
  useFavoritePendingStore(state => state.pending.has(itemId));

// Every query-key PREFIX whose cached data can hold items that render a heart. getQueriesData
// matches by prefix, so the first segment covers all variants (filters, infinite pages, ids).
// This is the one list to extend when a new item-bearing query is introduced — components must
// never patch favorite caches themselves.
//   albums/artists/tracks  → library + favorites infinite lists  (['tracks','infinite',{...}])
//   album/artist           → detail pages incl. their nested track/child items (['album', id])
//   recommend              → daily mix                            (['recommend','daily-mix',limit])
//   semantic-search        → text search results                 (['semantic-search', query])
const PATCH_QUERY_KEYS = [
  ['albums'],
  ['artists'],
  ['tracks'],
  ['album'],
  ['artist'],
  ['recommend'],
  ['semantic-search'],
] as const;

// Settle refetch targets. Deliberately EXCLUDES ['recommend'] and ['semantic-search']: those are
// derived/randomised lists where a refetch would reshuffle the daily mix or re-run the search, and
// the optimistic patch already reflects the only field that changed (the heart). The favorites-page
// lists live under albums/artists/tracks, so unliked items are reconciled (removed) when these
// refetch — that is the chosen "remain visible with an empty heart until settle, then drop" policy.
const INVALIDATE_QUERY_KEYS = [['albums'], ['artists'], ['tracks'], ['album'], ['artist']] as const;

function patchArrayItems(items: unknown[], itemId: string, isFavorite: boolean): boolean {
  let patched = false;
  for (const item of items) {
    if (patchFavoriteInData(item, itemId, isFavorite)) patched = true;
  }
  return patched;
}

function patchFavoriteInData(data: unknown, itemId: string, isFavorite: boolean): boolean {
  if (!data || typeof data !== 'object') return false;

  if (Array.isArray(data)) return patchArrayItems(data, itemId, isFavorite);

  const record = data as Record<string, unknown>;

  if (record.Id === itemId) {
    if (!record.UserData || typeof record.UserData !== 'object') {
      record.UserData = {
        PlaybackPositionTicks: 0,
        PlayCount: 0,
        IsFavorite: false,
        Played: false,
      };
    }
    (record.UserData as Record<string, unknown>).IsFavorite = isFavorite;
    return true;
  }

  let patched = false;
  for (const key of ['Items', 'pages'] as const) {
    if (Array.isArray(record[key])) {
      if (patchArrayItems(record[key] as unknown[], itemId, isFavorite)) patched = true;
    }
  }
  return patched;
}

function patchFavoriteInQueries(
  queryClient: ReturnType<typeof useQueryClient>,
  itemId: string,
  newValue: boolean
): Map<string, unknown> {
  const previousData = new Map<string, unknown>();
  for (const key of PATCH_QUERY_KEYS) {
    const queries = queryClient.getQueriesData({ queryKey: key });
    for (const [queryKey, data] of queries) {
      if (!data) continue;
      const keyStr = JSON.stringify(queryKey);
      const cloned = structuredClone(data);
      if (patchFavoriteInData(cloned, itemId, newValue)) {
        previousData.set(keyStr, data);
        queryClient.setQueryData(queryKey, cloned);
      }
    }
  }
  return previousData;
}

/**
 * The single favorite mutation for the entire app. Components render {@link FavoriteButton}, which
 * calls this hook — nothing else issues POST/DELETE /UserFavoriteItems. One optimistic patch updates
 * every relevant React Query cache plus the player store (now-playing + queue) so the heart flips
 * instantly and identically everywhere; one rollback restores them on error; one targeted invalidate
 * reconciles the canonical lists on settle.
 *
 * Repeated-click policy: pending is locked PER ITEM ID (useFavoritePendingStore), not per button.
 * The lock is taken synchronously when any FavoriteButton for the item is clicked and released on
 * settle, so every instance of that item (Songs row, Now Playing, Queue, Favorites, detail page)
 * disables together and a second click — on the same control or a different one — is rejected before
 * a second network call can start. This removes the concurrent-toggle race entirely.
 */
export function useFavoriteToggle() {
  const client = useAuthStore(state => state.client);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ itemId, isFavorite }: FavoriteToggleParams) => {
      if (!client) throw new Error('Not authenticated');
      const service = new FavoritesService(client);
      try {
        if (isFavorite) {
          await service.unmarkFavorite(itemId);
        } else {
          await service.markFavorite(itemId);
        }
      } catch (error) {
        // Offline: keep the optimistic heart and queue the change to replay on
        // reconnect. Online failures still surface so the UI rolls back.
        if (typeof navigator !== 'undefined' && !navigator.onLine) {
          await useOfflineStore.getState().queueFavorite(itemId, !isFavorite);
          return;
        }
        throw error;
      }
    },
    onMutate: async ({ itemId, isFavorite }: FavoriteToggleParams) => {
      const newValue = !isFavorite;

      await Promise.all(
        PATCH_QUERY_KEYS.map(async key => queryClient.cancelQueries({ queryKey: key }))
      );

      const previousData = patchFavoriteInQueries(queryClient, itemId, newValue);
      usePlayerStore.getState().patchTrackFavorite(itemId, newValue);

      return { previousData };
    },
    onSuccess: (_data, { itemId, isFavorite }) => {
      // Keep offline downloads in step with favorites: a new like is downloaded,
      // an unlike releases its 'favorite' hold. Skipped while offline — the like
      // was queued and the favorites set is reconciled on reconnect.
      if (typeof navigator !== 'undefined' && !navigator.onLine) return;
      const offline = useOfflineStore.getState();
      if (isFavorite) offline.removeFavorite(itemId).catch(() => {});
      else offline.autoFavorite(itemId).catch(() => {});
    },
    onError: (_error, variables, context) => {
      if (context?.previousData) {
        for (const [keyStr, prev] of context.previousData) {
          const queryKey = JSON.parse(keyStr) as unknown[];
          queryClient.setQueryData(queryKey, prev);
        }
      }
      // Restore the player-owned copies (now-playing + queue) to their pre-click value.
      usePlayerStore.getState().patchTrackFavorite(variables.itemId, variables.isFavorite);
    },
    onSettled: (_data, _error, variables) => {
      // Release the per-item lock (success or failure) so the heart becomes interactive again.
      useFavoritePendingStore.getState().end(variables.itemId);
      void Promise.all(
        INVALIDATE_QUERY_KEYS.map(async key => queryClient.invalidateQueries({ queryKey: key }))
      ).catch(() => {});
    },
  });
}
