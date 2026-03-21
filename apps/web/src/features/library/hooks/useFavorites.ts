import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FavoritesService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { usePlayerStore } from '@/features/player/stores/player.store';

interface FavoriteToggleParams {
  itemId: string;
  isFavorite: boolean;
}

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

  if (record.Id === itemId && record.UserData && typeof record.UserData === 'object') {
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

const FAVORITE_QUERY_KEYS = [['albums'], ['artists'], ['tracks'], ['album'], ['artist']] as const;

function patchFavoriteInQueries(
  queryClient: ReturnType<typeof useQueryClient>,
  itemId: string,
  newValue: boolean
): Map<string, unknown> {
  const previousData = new Map<string, unknown>();
  for (const key of FAVORITE_QUERY_KEYS) {
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
        FAVORITE_QUERY_KEYS.map(async key => queryClient.cancelQueries({ queryKey: key }))
      );

      const previousData = patchFavoriteInQueries(queryClient, itemId, newValue);

      const currentTrack = usePlayerStore.getState().currentTrack;
      if (currentTrack?.Id === itemId) {
        const baseUserData = currentTrack.UserData ?? {
          PlaybackPositionTicks: 0,
          PlayCount: 0,
          IsFavorite: false,
          Played: false,
        };
        usePlayerStore.setState({
          currentTrack: {
            ...currentTrack,
            UserData: { ...baseUserData, IsFavorite: newValue },
          },
        });
      }

      return {
        previousData,
        previousTrackFavorite: currentTrack?.Id === itemId ? isFavorite : null,
      };
    },
    onError: (_error, variables, context) => {
      if (!context?.previousData) return;
      for (const [keyStr, prev] of context.previousData) {
        const queryKey = JSON.parse(keyStr) as unknown[];
        queryClient.setQueryData(queryKey, prev);
      }

      if (context.previousTrackFavorite !== null && context.previousTrackFavorite !== undefined) {
        const currentTrack = usePlayerStore.getState().currentTrack;
        if (currentTrack?.Id === variables.itemId) {
          const baseUserData = currentTrack.UserData ?? {
            PlaybackPositionTicks: 0,
            PlayCount: 0,
            IsFavorite: false,
            Played: false,
          };
          usePlayerStore.setState({
            currentTrack: {
              ...currentTrack,
              UserData: { ...baseUserData, IsFavorite: context.previousTrackFavorite },
            },
          });
        }
      }
    },
    onSettled: () => {
      void Promise.all([
        queryClient.invalidateQueries({ queryKey: ['albums'] }),
        queryClient.invalidateQueries({ queryKey: ['artists'] }),
        queryClient.invalidateQueries({ queryKey: ['tracks'] }),
        queryClient.invalidateQueries({ queryKey: ['album'] }),
        queryClient.invalidateQueries({ queryKey: ['artist'] }),
      ]).catch(() => {});
    },
  });
}
