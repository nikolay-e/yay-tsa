import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { installBaseMock, login } from './helpers/media-fixtures';

// Backend-free favorite-sync suite (chromium-mocked project): every /api/* call is stubbed, so this
// runs without a live backend. It exercises the real cross-screen favorite flow in a browser:
// liking on Search, seeing the same track in Favorites, unliking there, and the heart reconciling
// back on Search — plus the rollback path when the server rejects the toggle.
//
//   npx playwright test --project=chromium-mocked

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

  installBaseMock(page);

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
}

function heartInRow(page: Page, trackName: string) {
  return page
    .getByTestId('track-row')
    .filter({ hasText: trackName })
    .getByRole('button', { name: /favorites/ });
}

test.describe('Favorite sync across screens (mocked backend)', () => {
  test('like on Search → shows in Favorites → unlike there → empty back on Search', async ({
    page,
  }) => {
    installMock(page);
    await login(page);

    await page.goto('/search');
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

    // Back on Search the heart is empty and backend-consistent after refetch.
    await page.goto('/search');
    await expect(heartInRow(page, 'Aurora')).toHaveAttribute('aria-pressed', 'false');
  });

  test('server rejects the toggle → optimistic fill rolls back to empty', async ({ page }) => {
    installMock(page, { failToggle: true });
    await login(page);

    await page.goto('/search');
    const heart = heartInRow(page, 'Borealis');
    await expect(heart).toHaveAttribute('aria-pressed', 'false');

    await heart.click();
    // It may flash filled optimistically, but once the 500 settles it must roll back to empty.
    await expect(heart).toHaveAttribute('aria-pressed', 'false');
  });
});
