import { test, expect, type Page, type Route } from '@playwright/test';
import { installBaseMock, login } from './helpers/media-fixtures';

// Backend-free search-UX suite (chromium-mocked project): /api/* is stubbed, so this runs without
// a live backend. It proves the debounced search keeps the previous grid mounted while typing
// (placeholderData) instead of swapping in a full-page spinner, and that keystrokes are coalesced
// into a single trailing request.
//
//   npx playwright test --project=chromium-mocked

const ALBUMS = Array.from({ length: 6 }, (_, i) => ({
  Id: `al${i + 1}`,
  Name: `Album ${i + 1}`,
  Type: 'MusicAlbum',
  Artists: ['Nova'],
  AlbumArtist: 'Nova',
  ProductionYear: 2020,
  ImageTags: {},
  UserData: { PlaybackPositionTicks: 0, PlayCount: 0, IsFavorite: false, Played: false },
}));

function installMock(page: Page, searchTermsSeen: string[]): void {
  installBaseMock(page);

  void page.route(/\/v1\/me\/(devices|audiobooks)(\?|$)/, (route: Route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  void page.route(/\/Items(\?|$)/, (route: Route) => {
    const url = new URL(route.request().url());
    const searchTerm = url.searchParams.get('SearchTerm');
    if (searchTerm) searchTermsSeen.push(searchTerm);
    const items = searchTerm
      ? ALBUMS.filter(a => a.Name.toLowerCase().includes(searchTerm.toLowerCase()))
      : ALBUMS;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: items, TotalRecordCount: items.length, StartIndex: 0 }),
    });
  });
}

test.describe('Search debounce (mocked backend)', () => {
  test('typing keeps the previous album grid mounted and coalesces requests', async ({ page }) => {
    const searchTermsSeen: string[] = [];
    installMock(page, searchTermsSeen);
    await login(page);

    await page.goto('/albums');
    const grid = page.getByTestId('albums-content');
    await expect(grid).toBeVisible();
    await expect(page.getByText('Album 1')).toBeVisible();

    await page.evaluate(() => {
      const gridElement = document.querySelector('[data-testid="albums-content"]');
      (window as unknown as { __gridDetached: boolean }).__gridDetached = false;
      const observer = new MutationObserver(() => {
        if (gridElement && !document.contains(gridElement)) {
          (window as unknown as { __gridDetached: boolean }).__gridDetached = true;
        }
      });
      observer.observe(document.body, { childList: true, subtree: true });
    });

    await page.getByTestId('search-input').pressSequentially('Album 3', { delay: 60 });

    await expect(grid).toBeVisible();
    await expect(page.getByText('Album 3')).toBeVisible();
    await expect(page.getByText('Album 1')).toHaveCount(0);

    const gridDetached = await page.evaluate(
      () => (window as unknown as { __gridDetached: boolean }).__gridDetached
    );
    expect(gridDetached).toBe(false);

    expect(searchTermsSeen.length).toBeLessThan('Album 3'.length);
    expect(searchTermsSeen[searchTermsSeen.length - 1]).toBe('Album 3');
  });
});
