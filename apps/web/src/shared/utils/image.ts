import { useAuthStore } from '@/features/auth/stores/auth.store';

export function useImageUrl() {
  const client = useAuthStore((state) => state.client);

  return {
    getImageUrl: (
      itemId: string,
      imageType: string = 'Primary',
      options?: { maxWidth?: number; maxHeight?: number; tag?: string }
    ) => {
      if (!client) return '';
      return client.getImageUrl(itemId, imageType, options);
    },
  };
}

export function getImagePlaceholder() {
  return 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><rect fill="%23333" width="1" height="1"/></svg>';
}
