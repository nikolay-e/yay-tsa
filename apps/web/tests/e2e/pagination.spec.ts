import { test, expect } from './fixtures/auth.fixture';

// Pins useInfiniteLibraryQuery: maxPages eviction + offset derived from lastPageParam (not the
// running sum). Mocks /Items deterministically so a regression in getNextPageParam shows up as
// duplicate or skipped album ids across the eviction boundary.
//
// NOTE: e2e runs against the dev server proxying to a live backend (playwright.config webServer).
// Run with `npm run test:e2e` once the backend is up; page.route mocks the album list itself.

const PAGE_SIZE = 50; // matches useInfiniteLibraryQuery default limit
const PAGES = 15; // > maxPages (10) to cross the eviction boundary
const TOTAL = PAGE_SIZE * PAGES;

test.describe('Infinite-query pagination correctness', () => {
  test('no duplicate or skipped album ids across the maxPages eviction boundary', async ({
    page,
  }) => {
    await page.route('**/Items*', async route => {
      const url = new URL(route.request().url());
      if (url.searchParams.get('IncludeItemTypes') !== 'MusicAlbum') {
        return route.fallback(); // let images and other Items calls hit the backend
      }
      const start = Number(url.searchParams.get('StartIndex') ?? '0');
      const limit = Number(url.searchParams.get('Limit') ?? PAGE_SIZE);
      const items = Array.from({ length: Math.max(0, Math.min(limit, TOTAL - start)) }, (_, i) => ({
        Id: `album-${start + i}`,
        Name: `Album ${start + i}`,
        Type: 'MusicAlbum',
        Artists: ['A'],
        ImageTags: {},
      }));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: items, TotalRecordCount: TOTAL }),
      });
    });

    await page.goto('/albums');
    const cards = page.getByTestId('album-card');
    await expect(cards.first()).toBeVisible({ timeout: 10000 });

    const loadMore = page.getByRole('button', { name: 'Load More' });
    for (let i = 0; i < PAGES - 1; i++) {
      await loadMore.scrollIntoViewIfNeeded();
      await loadMore.click();
      await expect(
        page.locator(`[data-testid="album-card"] a[href="/albums/album-${(i + 1) * PAGE_SIZE}"]`)
      ).toBeVisible();
    }

    // maxPages=10 → DOM retains exactly the last 10 pages = 500 cards.
    await expect(cards).toHaveCount(10 * PAGE_SIZE);

    const ids = await page
      .locator('[data-testid="album-card"] a[href^="/albums/"]')
      .evaluateAll(els =>
        els.map(e => (e as HTMLAnchorElement).getAttribute('href')!.split('/').pop()!)
      );
    expect(new Set(ids).size).toBe(ids.length); // no duplicates
    const nums = ids.map(id => Number(id.replace('album-', ''))).sort((a, b) => a - b);
    expect(nums[0]).toBe(TOTAL - 10 * PAGE_SIZE); // window starts at the 6th page
    expect(nums[nums.length - 1]).toBe(TOTAL - 1); // ends at the last id
    for (let k = 1; k < nums.length; k++) {
      expect(nums[k] - nums[k - 1]).toBe(1); // contiguous → no skips
    }
  });
});
