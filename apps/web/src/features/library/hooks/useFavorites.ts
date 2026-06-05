import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FavoritesService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { usePlayerStore } from '@/features/player/stores/player.store';

interface FavoriteToggleParams {
  itemId: string;
  isFavorite: boolean;
}

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
 * Repeated-click policy: each FavoriteButton owns this mutation, so `isPending` disables that button
 * until the request settles (no double-submit on one control). The optimistic patch means any OTHER
 * instance of the same item updates immediately and remains interactive, so cross-screen toggling is
 * always last-write-wins on the freshly-rendered state.
 */
export function useFavoriteToggle() {
  const client = useAuthStore(state => state.client);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ itemId, isFavorite }: FavoriteToggleParams) => {
      if (!client) throw new Error('Not authenticated');
      const service = new FavoritesService(client);
      if (isFavorite) {
        await service.unmarkFavorite(itemId);
      } else {
        await service.markFavorite(itemId);
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
    onSettled: () => {
      void Promise.all(
        INVALIDATE_QUERY_KEYS.map(async key => queryClient.invalidateQueries({ queryKey: key }))
      ).catch(() => {});
    },
  });
}
