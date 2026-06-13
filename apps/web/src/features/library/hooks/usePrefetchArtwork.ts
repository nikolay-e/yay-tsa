import { useEffect } from 'react';
import { type InfiniteData } from '@tanstack/react-query';
import { type ItemsResult } from '@yay-tsa/core';
import { useAuthStore } from '@/features/auth/stores/auth.store';
import { canPrefetchArtwork, warmImageUrl } from '@/shared/utils/prefetch-image';

const GRID_THUMB_CSS_PX = 160;
const MAX_IMAGES_PER_PAGE = 24;

function gridThumbPx(): number {
  const dpr =
    globalThis.window === undefined ? 1 : Math.min(globalThis.window.devicePixelRatio || 1, 2);
  return Math.round(GRID_THUMB_CSS_PX * dpr);
}

export interface ArtworkItem {
  Id: string;
  ImageTags?: { Primary?: string };
}

interface UsePrefetchArtworkOptions {
  data: InfiniteData<ItemsResult<ArtworkItem>> | undefined;
}

export function usePrefetchArtwork({ data }: UsePrefetchArtworkOptions) {
  const client = useAuthStore(state => state.client);

  useEffect(() => {
    if (!client || !data || !canPrefetchArtwork()) return;

    const thumbPx = gridThumbPx();

    for (const page of data.pages) {
      for (const item of (page.Items ?? []).slice(0, MAX_IMAGES_PER_PAGE)) {
        const tag = item.ImageTags?.Primary;
        if (!tag) continue;
        warmImageUrl(
          client.getImageUrl(item.Id, 'Primary', {
            maxWidth: thumbPx,
            maxHeight: thumbPx,
            tag,
          })
        );
      }
    }
  }, [client, data]);
}
