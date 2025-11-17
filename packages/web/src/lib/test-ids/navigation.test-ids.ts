/**
 * Test IDs for navigation-related components
 * Used by both Svelte components and Playwright tests for stable, DRY selectors
 */
export const NAVIGATION_TEST_IDS = {
  // BottomTabBar
  NAV_RECENT: 'nav-recent',
  NAV_ALBUMS: 'nav-albums',
  NAV_ARTISTS: 'nav-artists',
  NAV_SEARCH: 'nav-search',

  // Search page
  SEARCH_INPUT: 'search-input',
  SEARCH_ALBUMS_TAB: 'search-albums-tab',
  SEARCH_TRACKS_TAB: 'search-tracks-tab',
} as const;

export type NavigationTestId = typeof NAVIGATION_TEST_IDS[keyof typeof NAVIGATION_TEST_IDS];
