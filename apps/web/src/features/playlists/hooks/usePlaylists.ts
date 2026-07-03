import { useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { PlaylistsService, type AudioItem, type ItemsResult, type Playlist } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { useAuthenticatedQuery } from '@/features/auth/hooks/useAuthenticatedQuery';
import { useInfiniteLibraryQuery } from '@/features/library/hooks/useInfiniteLibraryQuery';

export const PLAYLISTS_QUERY_KEY = ['playlists'] as const;

export function usePlaylists() {
  return useAuthenticatedQuery([...PLAYLISTS_QUERY_KEY], async client =>
    new PlaylistsService(client).getPlaylists()
  );
}

export function usePlaylist(playlistId: string | undefined) {
  return useAuthenticatedQuery(
    ['playlist', playlistId],
    async client => {
      if (!playlistId) throw new Error('Playlist ID required');
      return new PlaylistsService(client).getPlaylist(playlistId);
    },
    { enabled: !!playlistId }
  );
}

export function useInfinitePlaylistItems(playlistId: string | undefined) {
  return useInfiniteLibraryQuery<AudioItem>({
    queryKey: ['playlist', playlistId, 'items'],
    enabled: !!playlistId,
    fetcher: async (client, params) => {
      if (!playlistId) throw new Error('Playlist ID required');
      return new PlaylistsService(client).getPlaylistItems(playlistId, params);
    },
  });
}

function useAuthenticatedClient() {
  const client = useAuthStore(state => state.client);
  return () => {
    if (!client) throw new Error('Not authenticated');
    return client;
  };
}

export function useCreatePlaylist() {
  const getClient = useAuthenticatedClient();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ name, itemIds }: { name: string; itemIds?: string[] }) =>
      new PlaylistsService(getClient()).createPlaylist({ name, itemIds }),
    onSuccess: (playlist: Playlist) => {
      queryClient.setQueryData(['playlist', playlist.Id], playlist);
      void queryClient.invalidateQueries({ queryKey: PLAYLISTS_QUERY_KEY });
    },
  });
}

export function useDeletePlaylist() {
  const getClient = useAuthenticatedClient();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (playlistId: string) =>
      new PlaylistsService(getClient()).deletePlaylist(playlistId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: PLAYLISTS_QUERY_KEY });
    },
  });
}

export function useAddToPlaylist() {
  const getClient = useAuthenticatedClient();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ playlistId, itemIds }: { playlistId: string; itemIds: string[] }) =>
      new PlaylistsService(getClient()).addItemsToPlaylist(playlistId, itemIds),
    onSuccess: (_data, { playlistId }) => {
      void queryClient.invalidateQueries({ queryKey: ['playlist', playlistId] });
      void queryClient.invalidateQueries({ queryKey: PLAYLISTS_QUERY_KEY });
    },
  });
}

export function useRemoveFromPlaylist() {
  const getClient = useAuthenticatedClient();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ playlistId, entryIds }: { playlistId: string; entryIds: string[] }) =>
      new PlaylistsService(getClient()).removeItemsFromPlaylist(playlistId, entryIds),
    onSuccess: (_data, { playlistId }) => {
      void queryClient.invalidateQueries({ queryKey: ['playlist', playlistId] });
      void queryClient.invalidateQueries({ queryKey: PLAYLISTS_QUERY_KEY });
    },
  });
}

type PlaylistItemsData = InfiniteData<ItemsResult<AudioItem>>;

function reorderInfiniteItems(
  data: PlaylistItemsData,
  fromIndex: number,
  toIndex: number
): PlaylistItemsData {
  const flat = data.pages.flatMap(page => page.Items);
  if (fromIndex < 0 || fromIndex >= flat.length) return data;
  const [moved] = flat.splice(fromIndex, 1);
  flat.splice(Math.min(toIndex, flat.length), 0, moved!);
  const reindexed = flat.map((item, index) => ({ ...item, PlaylistItemId: String(index) }));
  let offset = 0;
  const pages = data.pages.map(page => {
    const slice = reindexed.slice(offset, offset + page.Items.length);
    offset += page.Items.length;
    return { ...page, Items: slice };
  });
  return { ...data, pages };
}

export function useMovePlaylistItem(playlistId: string | undefined) {
  const getClient = useAuthenticatedClient();
  const queryClient = useQueryClient();
  const itemsKey = ['playlist', playlistId, 'items'];

  return useMutation({
    mutationFn: async ({ fromIndex, toIndex }: { fromIndex: number; toIndex: number }) => {
      if (!playlistId) throw new Error('Playlist ID required');
      return new PlaylistsService(getClient()).movePlaylistItem(
        playlistId,
        String(fromIndex),
        toIndex
      );
    },
    onMutate: async ({ fromIndex, toIndex }) => {
      await queryClient.cancelQueries({ queryKey: itemsKey });
      const previous = queryClient.getQueriesData<PlaylistItemsData>({ queryKey: itemsKey });
      for (const [queryKey, data] of previous) {
        if (!data) continue;
        queryClient.setQueryData(queryKey, reorderInfiniteItems(data, fromIndex, toIndex));
      }
      return { previous };
    },
    onError: (_error, _variables, context) => {
      for (const [queryKey, data] of context?.previous ?? []) {
        queryClient.setQueryData(queryKey, data);
      }
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: itemsKey });
    },
  });
}
