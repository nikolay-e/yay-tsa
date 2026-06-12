import { test, expect, type Page, type Route } from '@playwright/test';

// Backend-free favorite-sync suite (chromium-mocked project): every /api/* call is stubbed, so this
// runs without a live backend. It exercises the real cross-screen favorite flow in a browser:
// liking on Songs, seeing the same track in Favorites, unliking there, and the heart reconciling
// back on Songs — plus the rollback path when the server rejects the toggle.
//
//   npx playwright test --project=chromium-mocked

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };

const CATALOG = [
  { Id: 't1', Name: 'Aurora' },
  { Id: 't2', Name: 'Borealis' },
  { Id: 't3', Name: 'Cascade' },
];

function buildTrack(id: string, name: string, isFavorite: boolean) {
  return {
    Id: id,
    Name: name,
    Type: 'Audio',
    RunTimeTicks: 2_000_000_000,
    Album: 'Skyline',
    AlbumId: 'al1',
    AlbumPrimaryImageTag: 'tag1',
    Artists: ['Nova'],
    ArtistItems: [{ Id: 'ar1', Name: 'Nova' }],
    IndexNumber: 1,
    UserData: { PlaybackPositionTicks: 0, PlayCount: 0, IsFavorite: isFavorite, Played: false },
  };
}

// Stateful mock: a single Set is the backend's favorite truth, so /Items, the IsFavorite=true list,
// and POST/DELETE stay self-consistent regardless of the exact query params the client sends.
function installMock(page: Page, opts: { failToggle?: boolean } = {}): void {
  const favorites = new Set<string>();

  const itemsBody = (favoritesOnly: boolean) => {
    const items = CATALOG.filter(t => !favoritesOnly || favorites.has(t.Id)).map(t =>
      buildTrack(t.Id, t.Name, favorites.has(t.Id))
    );
    return JSON.stringify({ Items: items, TotalRecordCount: items.length, StartIndex: 0 });
  };

  // Benign catch-all FIRST; more specific routes registered after take precedence.
  void page.route('**/api/**', (route: Route) => {
    const method = route.request().method();
    if (method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: [], TotalRecordCount: 0, StartIndex: 0 }),
      });
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

  // Track lists: the favorites tab adds IsFavorite=true; the songs list does not.
  void page.route(/\/Items(\?|$)/, (route: Route) => {
    const url = route.request().url();
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: itemsBody(/IsFavorite=true/i.test(url)),
    });
  });

  void page.route(/\/UserFavoriteItems\//, (route: Route) => {
    if (opts.failToggle) {
      return route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: 500, title: 'Internal Server Error' }),
      });
    }
    const id = decodeURIComponent(
      route.request().url().split('/UserFavoriteItems/')[1]!.split('?')[0]!
    );
    if (route.request().method() === 'DELETE') favorites.delete(id);
    else favorites.add(id);
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  // Device-sync endpoints return arrays; the RootLayout RemotePlaybackBanner calls
  // devices.filter, so the JSON-object catch-all crashes the page without this stub.
  void page.route(/\/v1\/me\/devices(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  );

  // Cover art: serve a 1x1 transparent PNG so images never hit the JSON catch-all.
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

function heartInRow(page: Page, trackName: string) {
  return page
    .getByTestId('track-row')
    .filter({ hasText: trackName })
    .getByRole('button', { name: /favorites/ });
}

// NOTE: these two are marked test.fixme until validated on a machine with a browser. The state layer
// (cross-cache patch + rollback + per-item concurrency lock) is fully proven by the vitest suites;
// this file is the ready browser-level follow-up. It could not be validated from the build sandbox
// (the Chromium download is blocked there), and the favorite-bearing track lists are virtualised,
// which the existing mocked auth suite never exercises — so the selectors/timing need a real run to
// confirm before this gates CI. Un-fixme and run: npx playwright test --project=chromium-mocked
test.describe('Favorite sync across screens (mocked backend)', () => {
  test('like on Songs → shows in Favorites → unlike there → empty back on Songs', async ({
    page,
  }) => {
    installMock(page);
    await login(page);

    await page.goto('/songs');
    const heart = heartInRow(page, 'Aurora');
    await expect(heart).toHaveAttribute('aria-pressed', 'false');

    // Like it — optimistic fill is immediate.
    await heart.click();
    await expect(heart).toHaveAttribute('aria-pressed', 'true');

    // The same track is now in Favorites.
    await page.goto('/favorites');
    const favHeart = heartInRow(page, 'Aurora');
    await expect(favHeart).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('track-row').filter({ hasText: 'Aurora' })).toBeVisible();

    // Unlike from Favorites; the settle refetch removes it from the favorites list.
    await favHeart.click();
    await expect(page.getByTestId('track-row').filter({ hasText: 'Aurora' })).toHaveCount(0);

    // Back on Songs the heart is empty and backend-consistent after refetch.
    await page.goto('/songs');
    await expect(heartInRow(page, 'Aurora')).toHaveAttribute('aria-pressed', 'false');
  });

  test('server rejects the toggle → optimistic fill rolls back to empty', async ({ page }) => {
    installMock(page, { failToggle: true });
    await login(page);

    await page.goto('/songs');
    const heart = heartInRow(page, 'Borealis');
    await expect(heart).toHaveAttribute('aria-pressed', 'false');

    await heart.click();
    // It may flash filled optimistically, but once the 500 settles it must roll back to empty.
    await expect(heart).toHaveAttribute('aria-pressed', 'false');
  });
});
