/**
 * Image URL utilities
 */

import { get } from 'svelte/store';
import { client } from '../stores/auth.js';

/**
 * Image size presets
 */
type ImageSize = 'small' | 'medium' | 'large';

const IMAGE_SIZE_DIMENSIONS: Record<ImageSize, { maxWidth: number; maxHeight: number }> = {
  small: { maxWidth: 150, maxHeight: 150 },
  medium: { maxWidth: 300, maxHeight: 300 },
  large: { maxWidth: 600, maxHeight: 600 },
};

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
  }
): string {
  const $client = get(client);

  if (!$client) {
    return '';
  }

  return $client.getImageUrl(itemId, imageType, options);
}

/**
 * Get album art URL with preset size
 */
export function getAlbumArtUrl(itemId: string, size: ImageSize = 'medium'): string {
  const dimensions = IMAGE_SIZE_DIMENSIONS[size];
  return getImageUrl(itemId, 'Primary', dimensions);
}
