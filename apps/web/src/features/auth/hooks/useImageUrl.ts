import { useAuthStore } from '@/features/auth/stores/auth.store';
import { getImagePlaceholder } from '@/shared/utils/image-placeholder';

export { getImagePlaceholder };

export function useImageUrl() {
  const client = useAuthStore(state => state.client);

  return {
    getImageUrl: (
      itemId: string,
      imageType: string = 'Primary',
      options?: { maxWidth?: number; maxHeight?: number; tag?: string }
    ) => {
      if (!client || !itemId) return getImagePlaceholder();
      return client.getImageUrl(itemId, imageType, options);
    },
  };
}
