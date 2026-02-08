import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FavoritesService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { usePlayerStore } from '@/features/player/stores/player.store';

interface FavoriteToggleParams {
  itemId: string;
  isFavorite: boolean;
}

function patchFavoriteInData(data: unknown, itemId: string, isFavorite: boolean): boolean {
  if (!data || typeof data !== 'object') return false;

  let patched = false;

  if (Array.isArray(data)) {
    for (const item of data) {
      if (patchFavoriteInData(item, itemId, isFavorite)) patched = true;
    }
    return patched;
  }

  const record = data as Record<string, unknown>;

  if (record.Id === itemId && record.UserData && typeof record.UserData === 'object') {
    (record.UserData as Record<string, unknown>).IsFavorite = isFavorite;
    return true;
  }

  if (record.Items && Array.isArray(record.Items)) {
    for (const item of record.Items) {
      if (patchFavoriteInData(item, itemId, isFavorite)) patched = true;
    }
  }

  if (record.pages && Array.isArray(record.pages)) {
    for (const page of record.pages) {
      if (patchFavoriteInData(page, itemId, isFavorite)) patched = true;
    }
  }

  return patched;
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
    onMutate: ({ itemId, isFavorite }: FavoriteToggleParams) => {
      const newValue = !isFavorite;

      const queryCache = queryClient.getQueryCache();
      const queries = queryCache.getAll();

      const previousData = new Map<string, unknown>();

      for (const query of queries) {
        const key = query.queryKey;
        const keyStr = JSON.stringify(key);
        const data = query.state.data;
        if (!data) continue;

        const cloned = structuredClone(data);
        if (patchFavoriteInData(cloned, itemId, newValue)) {
          previousData.set(keyStr, data);
          queryClient.setQueryData(key, cloned);
        }
      }

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
      const queryCache = queryClient.getQueryCache();
      for (const query of queryCache.getAll()) {
        const keyStr = JSON.stringify(query.queryKey);
        const prev = context.previousData.get(keyStr);
        if (prev !== undefined) {
          queryClient.setQueryData(query.queryKey, prev);
        }
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
      void queryClient.invalidateQueries({ queryKey: ['albums'] });
      void queryClient.invalidateQueries({ queryKey: ['artists'] });
      void queryClient.invalidateQueries({ queryKey: ['tracks'] });
      void queryClient.invalidateQueries({ queryKey: ['album'] });
      void queryClient.invalidateQueries({ queryKey: ['artist'] });
    },
  });
}
