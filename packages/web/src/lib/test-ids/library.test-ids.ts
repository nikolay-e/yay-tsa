/**
 * Test IDs for library-related components
 * Used by both Svelte components and Playwright tests for stable, DRY selectors
 */
export const LIBRARY_TEST_IDS = {
  // AlbumCard component
  ALBUM_CARD: 'album-card',
  ALBUM_COVER: 'album-cover',
  ALBUM_TITLE: 'album-title',
  ALBUM_ARTIST: 'album-artist', // future: if needed

  // TrackList component
  TRACK_ROW: 'track-row',
  TRACK_TITLE: 'track-title',

  // Album detail page
  ALBUM_DETAIL_TITLE: 'album-detail-title',
  ALBUM_PLAY_BUTTON: 'play-button',
  ALBUM_SHUFFLE_BUTTON: 'album-shuffle-button', // prefixed to avoid collision with player
  ALBUM_FAVORITE_BUTTON: 'favorite-button', // future
  ALBUM_BACK_BUTTON: 'back-button', // future
} as const;

export type LibraryTestId = (typeof LIBRARY_TEST_IDS)[keyof typeof LIBRARY_TEST_IDS];
