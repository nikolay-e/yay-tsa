/**
 * Centralized test ID registry for yaytsa
 *
 * Single source of truth for all data-testid attributes.
 * Imported by both Svelte components and Playwright Page Objects.
 *
 * Usage in Svelte:
 *   import { PLAYER_TEST_IDS } from '$lib/shared/test-ids';
 *   <button data-testid={PLAYER_TEST_IDS.PLAY_PAUSE_BUTTON}>
 *
 * Usage in Playwright:
 *   import { PLAYER_TEST_IDS } from '../../../src/lib/shared/test-ids';
 *   this.playButton = page.getByTestId(PLAYER_TEST_IDS.PLAY_PAUSE_BUTTON);
 */

export * from './player.test-ids';
export * from './library.test-ids';
export * from './navigation.test-ids';

import { PLAYER_TEST_IDS } from './player.test-ids';
import { LIBRARY_TEST_IDS } from './library.test-ids';
import { NAVIGATION_TEST_IDS } from './navigation.test-ids';

/**
 * Combined test IDs object for convenience
 * Prefer importing specific domain objects (PLAYER_TEST_IDS, etc.) for better tree-shaking
 */
export const TEST_IDS = {
  ...PLAYER_TEST_IDS,
  ...LIBRARY_TEST_IDS,
  ...NAVIGATION_TEST_IDS,
} as const;
