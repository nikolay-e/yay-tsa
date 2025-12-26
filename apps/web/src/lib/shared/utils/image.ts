/**
 * Image URL utilities
 *
 * Architecture Note: This module uses dependency injection pattern to avoid
 * circular imports between shared/ and features/. The client is set via
 * setImageClient() called from auth.store.ts.
 */

import type { MediaServerClient } from '@yaytsa/core';

type ImageSize = 'small' | 'medium' | 'large';

const IMAGE_SIZE_DIMENSIONS: Record<ImageSize, { maxWidth: number; maxHeight: number }> = {
  small: { maxWidth: 150, maxHeight: 150 },
  medium: { maxWidth: 300, maxHeight: 300 },
  large: { maxWidth: 600, maxHeight: 600 },
};

// Client instance set via dependency injection from auth.store.ts
let cachedClient: MediaServerClient | null = null;

export function setImageClient(client: MediaServerClient | null): void {
  cachedClient = client;
}

/**
 * Get image URL for an item
 */
export function getImageUrl(
  itemId: string,
  imageType: string = 'Primary',
  options?: {
    tag?: string;
    maxWidth?: number;
    maxHeight?: number;
    quality?: number;
    format?: 'webp' | 'jpg' | 'png';
  }
): string {
  if (!cachedClient) {
    return '';
  }

  if (!itemId || itemId.trim() === '') {
    return '';
  }

  return cachedClient.getImageUrl(itemId, imageType, options);
}

/**
 * Get album art URL with preset size
 */
export function getAlbumArtUrl(itemId: string, size: ImageSize = 'medium', tag?: string): string {
  const dimensions = IMAGE_SIZE_DIMENSIONS[size];
  return getImageUrl(itemId, 'Primary', { ...dimensions, tag, format: 'webp', quality: 85 });
}

/**
 * Get responsive srcset for album art (1x/2x/3x pixel densities)
 * For Retina and high-DPI displays
 */
export function getAlbumArtSrcSet(
  itemId: string,
  tag?: string,
  size: ImageSize = 'medium'
): string {
  const baseDimensions = IMAGE_SIZE_DIMENSIONS[size];

  const srcset = [1, 2].map(pixelRatio => {
    const url = getImageUrl(itemId, 'Primary', {
      ...baseDimensions,
      maxWidth: baseDimensions.maxWidth * pixelRatio,
      maxHeight: baseDimensions.maxHeight * pixelRatio,
      tag,
      format: 'webp',
      quality: 85,
    });
    return `${url} ${pixelRatio}x`;
  });

  return srcset.join(', ');
}

/**
 * Get artist image URL with preset size
 */
export function getArtistImageUrl(
  artistId: string,
  size: ImageSize = 'medium',
  tag?: string
): string {
  const dimensions = IMAGE_SIZE_DIMENSIONS[size];
  return getImageUrl(artistId, 'Primary', { ...dimensions, tag, format: 'webp', quality: 85 });
}

/**
 * Get responsive srcset for artist image (1x/2x pixel densities)
 */
export function getArtistImageSrcSet(
  artistId: string,
  tag?: string,
  size: ImageSize = 'medium'
): string {
  const baseDimensions = IMAGE_SIZE_DIMENSIONS[size];

  const srcset = [1, 2].map(pixelRatio => {
    const url = getImageUrl(artistId, 'Primary', {
      ...baseDimensions,
      maxWidth: baseDimensions.maxWidth * pixelRatio,
      maxHeight: baseDimensions.maxHeight * pixelRatio,
      tag,
      format: 'webp',
      quality: 85,
    });
    return `${url} ${pixelRatio}x`;
  });

  return srcset.join(', ');
}
