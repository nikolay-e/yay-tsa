/**
 * Test IDs for player-related components
 * Used by both Svelte components and Playwright tests for stable, DRY selectors
 */
export const PLAYER_TEST_IDS = {
  // PlayerBar container
  PLAYER_BAR: 'player-bar',

  // Now playing info (PlayerBar)
  CURRENT_TRACK_TITLE: 'current-track-title',
  CURRENT_TRACK_ARTIST: 'current-track-artist',

  // Playback controls (Controls.svelte)
  PLAY_PAUSE_BUTTON: 'play-pause-button',
  NEXT_BUTTON: 'next-button',
  PREVIOUS_BUTTON: 'previous-button',

  // Seek controls (SeekBar.svelte)
  SEEK_SLIDER: 'seek-slider',
  CURRENT_TIME: 'current-time',
  TOTAL_TIME: 'total-time',

  // Volume controls (future: VolumeModal.svelte)
  VOLUME_SLIDER: 'volume-slider',

  // Queue controls (future)
  QUEUE_BUTTON: 'queue-button',

  // Sleep timer
  SLEEP_TIMER_BUTTON: 'sleep-timer-button',
} as const;

export type PlayerTestId = (typeof PLAYER_TEST_IDS)[keyof typeof PLAYER_TEST_IDS];
