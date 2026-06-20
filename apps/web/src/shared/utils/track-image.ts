import { getImagePlaceholder } from '@/shared/utils/image-placeholder';

interface TrackImageOptions {
  albumId?: string;
  albumPrimaryImageTag?: string;
  trackId: string;
  maxWidth?: number;
  maxHeight?: number;
}

type GetImageUrlFn = (
  id: string,
  type: string,
  opts?: { tag?: string; maxWidth?: number; maxHeight?: number }
) => string;

export function getTrackImageUrl(getImageUrl: GetImageUrlFn, options: TrackImageOptions): string {
  const { albumId, albumPrimaryImageTag, trackId, maxWidth = 64, maxHeight = 64 } = options;

  // Only request a cover the item actually advertises. Without a Primary image tag the server has
  // no cover to serve, so requesting it guarantees a 404 (forwarded as a ResourceError); render the
  // CSS placeholder instead, mirroring MediaCard's tag-gated rendering.
  if (albumPrimaryImageTag) {
    return getImageUrl(albumId ?? trackId, 'Primary', {
      tag: albumPrimaryImageTag,
      maxWidth,
      maxHeight,
    });
  }

  return getImagePlaceholder();
}
