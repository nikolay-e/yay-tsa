import { test, expect } from './fixtures/auth.fixture';
import { LIBRARY_TEST_IDS, FAVORITES_TEST_IDS } from './helpers/test-ids';
import { PlayerBar } from './page-objects/PlayerBar';

// Regression guard for the rendering perf work (React.memo on AlbumCard/ArtistCard/TrackListRow
// with comparators that ignore inline onPlay/onPause/onReorder identity, useMemo'd flatMap
// derivations, and the ref-based InfiniteScrollFooter). The risk these introduce is stale/broken
// UI: a memoized row keeping an out-of-date action, or a favorite toggle not reflecting because the
// comparator skipped a re-render. These tests exercise the real user path — append a page, then
// interact — and skip gracefully on an empty library so they stay green without seeded data.

test.describe('List perf regression (memo + pagination + tab switch)', () => {
  test('albums: a card stays interactive after an infinite-scroll append', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/albums');
    const cards = page.getByTestId(LIBRARY_TEST_IDS.ALBUM_CARD);
    const hasAlbums = await cards
      .first()
      .isVisible({ timeout: 10000 })
      .catch(() => false);
    test.skip(!hasAlbums, 'library has no albums');

    const before = await cards.count();
    const loadMore = page.getByRole('button', { name: 'Load More' });
    if (await loadMore.isVisible().catch(() => false)) {
      await loadMore.scrollIntoViewIfNeeded();
      await loadMore.click();
      // After append the grid re-renders; memoized cards whose album identity is unchanged must
      // not be torn down. DOM count must grow (the new page is added, old cards preserved).
      await expect(async () => {
        expect(await cards.count()).toBeGreaterThan(before);
      }).toPass({ timeout: 10000 });
    }

    // The play overlay's onPlay closure identity is deliberately ignored by AlbumCard's memo
    // comparator. Clicking it after the re-render proves the (stable) action still fires.
    const target = cards.nth(before > 0 ? before : 0);
    await target.scrollIntoViewIfNeeded();
    const playButton = target.getByRole('button', { name: /^Play / });
    await playButton.click();

    const player = new PlayerBar(page);
    await expect(player.playerBar).toBeVisible({ timeout: 10000 });
    await expect(async () => {
      expect(await player.isPlaying()).toBe(true);
    }).toPass({ timeout: 10000 });
    await player.pause();
  });

  test('songs: favorite toggle updates the row without corrupting siblings', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/songs');
    const rows = page.getByTestId(LIBRARY_TEST_IDS.TRACK_ROW);
    const hasRows = await rows
      .first()
      .isVisible({ timeout: 10000 })
      .catch(() => false);
    test.skip(!hasRows, 'library has no songs');
    test.skip((await rows.count()) < 2, 'need at least two songs to check sibling isolation');

    const favOf = (row: ReturnType<typeof rows.nth>) =>
      row.getByRole('button', { name: /favorite/i });
    const first = rows.nth(0);
    const second = rows.nth(1);

    const firstBefore = (await favOf(first).getAttribute('aria-pressed')) ?? 'false';
    const secondBefore = (await favOf(second).getAttribute('aria-pressed')) ?? 'false';

    await favOf(first).click();
    // Toggled row reflects the new state (its track object identity changes, so the memoized row
    // re-renders) ...
    await expect(favOf(first)).toHaveAttribute(
      'aria-pressed',
      firstBefore === 'true' ? 'false' : 'true'
    );
    // ... and the sibling is untouched.
    await expect(favOf(second)).toHaveAttribute('aria-pressed', secondBefore);

    // revert to leave state clean
    await favOf(first).click();
    await expect(favOf(first)).toHaveAttribute('aria-pressed', firstBefore);
  });

  test('favorites: tab round-trip keeps the page mounted and rendering', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/favorites');
    const favoritesPage = page.getByTestId(FAVORITES_TEST_IDS.PAGE);
    await expect(favoritesPage).toBeVisible({ timeout: 10000 });

    const tracksTab = page.getByRole('button', { name: 'Tracks' });
    const albumsTab = page.getByRole('button', { name: 'Albums' });
    const artistsTab = page.getByRole('button', { name: 'Artists' });

    // Switching tabs mounts/unmounts the per-tab infinite lists and re-runs the sort hooks; the
    // page must stay mounted and each tab must settle into content-or-empty without throwing.
    for (const tab of [albumsTab, artistsTab, tracksTab]) {
      await tab.click();
      await expect(favoritesPage).toBeVisible();
      await expect(async () => {
        const settled = await page.evaluate(() => document.readyState === 'complete');
        expect(settled).toBe(true);
      }).toPass({ timeout: 10000 });
    }
  });
});
