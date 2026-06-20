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

  // The image endpoint resolves covers by id and ignores the tag, so request the album cover by
  // album id (falling back to the track id, which serves track-level/embedded art). The tag is
  // passed only as a cache hint when present. The CSS placeholder is the fallback only when there
  // is genuinely no id to resolve a cover from; a coverless item returns a clean 404 the <img>
  // onError already handles.
  const imageId = albumId ?? trackId;
  if (imageId) {
    return getImageUrl(imageId, 'Primary', {
      tag: albumPrimaryImageTag,
      maxWidth,
      maxHeight,
    });
  }

  return getImagePlaceholder();
}
