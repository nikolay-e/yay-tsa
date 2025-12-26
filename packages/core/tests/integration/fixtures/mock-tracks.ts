/**
 * Mock Track Data
 * Realistic mock data structures that mimic Media Server API responses
 * Used for testing queue logic without server interaction
 */

import type { AudioItem } from '../../../src/index.js';

/**
 * Basic 3-track album for simple queue scenarios
 */
export const mockAlbumTracks: AudioItem[] = [
  {
    Id: 'track-1',
    Name: 'Opening Track',
    Type: 'Audio',
    RunTimeTicks: 2400000000, // 4 minutes
    AlbumId: 'album-1',
    Album: 'Test Album',
    Artists: ['Test Artist'],
    IndexNumber: 1,
  },
  {
    Id: 'track-2',
    Name: 'Middle Track',
    Type: 'Audio',
    RunTimeTicks: 2500000000, // 4:10
    AlbumId: 'album-1',
    Album: 'Test Album',
    Artists: ['Test Artist'],
    IndexNumber: 2,
  },
  {
    Id: 'track-3',
    Name: 'Closing Track',
    Type: 'Audio',
    RunTimeTicks: 2600000000, // 4:20
    AlbumId: 'album-1',
    Album: 'Test Album',
    Artists: ['Test Artist'],
    IndexNumber: 3,
  },
];

/**
 * Extended 10-track album for advanced queue scenarios
 */
export const mockLargeAlbum: AudioItem[] = Array.from({ length: 10 }, (_, i) => ({
  Id: `track-large-${i + 1}`,
  Name: `Track ${i + 1}`,
  Type: 'Audio' as const,
  RunTimeTicks: 2400000000 + i * 100000000, // Varying lengths
  AlbumId: 'album-large',
  Album: 'Large Test Album',
  Artists: ['Test Artist'],
  IndexNumber: i + 1,
}));

/**
 * Mixed tracks from different albums (for playlist scenarios)
 */
export const mockMixedTracks: AudioItem[] = [
  {
    Id: 'mix-track-1',
    Name: 'Rock Song',
    Type: 'Audio',
    RunTimeTicks: 3000000000,
    AlbumId: 'album-rock',
    Album: 'Rock Album',
    Artists: ['Rock Artist'],
    IndexNumber: 1,
  },
  {
    Id: 'mix-track-2',
    Name: 'Jazz Song',
    Type: 'Audio',
    RunTimeTicks: 4000000000,
    AlbumId: 'album-jazz',
    Album: 'Jazz Album',
    Artists: ['Jazz Artist'],
    IndexNumber: 1,
  },
  {
    Id: 'mix-track-3',
    Name: 'Classical Piece',
    Type: 'Audio',
    RunTimeTicks: 10000000000, // 16:40 - long classical piece
    AlbumId: 'album-classical',
    Album: 'Classical Album',
    Artists: ['Orchestra'],
    IndexNumber: 1,
  },
];

/**
 * Edge case: Very short track (< 1 second)
 */
export const mockShortTrack: AudioItem = {
  Id: 'track-short',
  Name: 'Short Jingle',
  Type: 'Audio',
  RunTimeTicks: 5000000, // 0.5 seconds
  AlbumId: 'album-short',
  Album: 'Short Tracks',
  Artists: ['Jingle Artist'],
  IndexNumber: 1,
};

/**
 * Edge case: Very long track (2+ hours)
 */
export const mockLongTrack: AudioItem = {
  Id: 'track-long',
  Name: 'Epic Symphony',
  Type: 'Audio',
  RunTimeTicks: 72000000000, // 2 hours
  AlbumId: 'album-long',
  Album: 'Long Form',
  Artists: ['Composer'],
  IndexNumber: 1,
};

/**
 * Multiple albums worth of tracks (for large queue testing)
 */
export const mockMassiveCatalog: AudioItem[] = Array.from({ length: 500 }, (_, i) => ({
  Id: `massive-track-${i + 1}`,
  Name: `Track ${i + 1}`,
  Type: 'Audio' as const,
  RunTimeTicks: 2400000000,
  AlbumId: `album-${Math.floor(i / 10)}`,
  Album: `Album ${Math.floor(i / 10)}`,
  Artists: [`Artist ${Math.floor(i / 50)}`],
  IndexNumber: (i % 10) + 1,
}));

/**
 * Helper: Create custom track for specific test scenarios
 */
export function createMockTrack(
  id: string,
  name: string,
  options?: {
    durationSeconds?: number;
    albumId?: string;
    albumName?: string;
    artist?: string;
    trackNumber?: number;
  }
): AudioItem {
  const durationTicks = (options?.durationSeconds || 240) * 10000000;

  return {
    Id: id,
    Name: name,
    Type: 'Audio',
    RunTimeTicks: durationTicks,
    AlbumId: options?.albumId || 'album-custom',
    Album: options?.albumName || 'Custom Album',
    Artists: [options?.artist || 'Custom Artist'],
    IndexNumber: options?.trackNumber || 1,
  };
}
