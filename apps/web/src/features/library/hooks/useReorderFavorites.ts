import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FavoritesService } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';

export function useReorderFavorites() {
  const client = useAuthStore(state => state.client);
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (itemIds: string[]) => {
      if (!client) throw new Error('Not authenticated');
      const service = new FavoritesService(client);
      await service.reorderFavorites(itemIds);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['albums'] });
      void queryClient.invalidateQueries({ queryKey: ['artists'] });
      void queryClient.invalidateQueries({ queryKey: ['tracks'] });
    },
  });
}
