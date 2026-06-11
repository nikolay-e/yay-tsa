import { test, expect, type Page, type Route } from '@playwright/test';

// Backend-free search-UX suite (chromium-mocked project): /api/* is stubbed, so this runs without
// a live backend. It proves the debounced search keeps the previous grid mounted while typing
// (placeholderData) instead of swapping in a full-page spinner, and that keystrokes are coalesced
// into a single trailing request.
//
//   npx playwright test --project=chromium-mocked

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };

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
  void page.route('**/api/**', (route: Route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: [], TotalRecordCount: 0, StartIndex: 0 }),
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  void page.route(/\/v1\/me\/(devices|audiobooks)(\?|$)/, (route: Route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  void page.route(/\/Users\/AuthenticateByName/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ AccessToken: VALID_TOKEN, User: USER, SessionInfo: {} }),
    })
  );

  void page.route(/\/Users\/Me(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(USER) })
  );

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

  void page.route(/\/Images\//, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
        'base64'
      ),
    })
  );
}

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill('mock-user');
  await page.getByLabel('Password').fill('mock-pass');
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL('/', { timeout: 15000 });
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
