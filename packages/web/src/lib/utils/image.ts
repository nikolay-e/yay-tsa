/**
 * Image URL utilities
 */

import { client } from '../stores/auth.js';
import type { JellyfinClient } from '@yaytsa/core';

/**
 * Image size presets
 */
type ImageSize = 'small' | 'medium' | 'large';

const IMAGE_SIZE_DIMENSIONS: Record<ImageSize, { maxWidth: number; maxHeight: number }> = {
  small: { maxWidth: 150, maxHeight: 150 },
  medium: { maxWidth: 300, maxHeight: 300 },
  large: { maxWidth: 600, maxHeight: 600 },
};

// Cache client instance to avoid repeated get() calls (performance optimization)
let cachedClient: JellyfinClient | null = null;
client.subscribe($client => {
  cachedClient = $client;
});

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
 * Get responsive srcset with width descriptors (for viewport-based sizing)
 */
export function getResponsiveImageSrcSet(itemId: string, tag?: string): string {
  const widths = [480, 768, 1024, 1920];

  const srcset = widths.map(width => {
    const url = getImageUrl(itemId, 'Primary', {
      maxWidth: width,
      maxHeight: width,
      tag,
      format: 'webp',
    });
    return `${url} ${width}w`;
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
