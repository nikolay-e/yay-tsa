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

  if (albumPrimaryImageTag) {
    return getImageUrl(albumId ?? trackId, 'Primary', {
      tag: albumPrimaryImageTag,
      maxWidth,
      maxHeight,
    });
  }

  if (albumId) {
    return getImageUrl(albumId, 'Primary', { maxWidth, maxHeight });
  }

  return getImagePlaceholder();
}
