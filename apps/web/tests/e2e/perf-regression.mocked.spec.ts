import { test, expect, type Page, type Route } from '@playwright/test';

// Backend-free regression suite for the rendering perf work (React.memo on AlbumCard/TrackListRow
// with comparators that ignore inline onPlay/onPause identity, useMemo'd flatMap derivations,
// ref-based InfiniteScrollFooter). Runs in a real browser under the `chromium-mocked` project with
// every API call stubbed — no live backend:
//
//   npx playwright test --project=chromium-mocked
//
// Covers the memo risks the unit test can't (real browser, real query cache + refetch): favorite
// toggle stays isolated to its row and survives the post-mutation refetch, infinite-scroll append
// keeps the grid correct, and Favorites tab switching stays mounted. (play/pause needs a real audio
// stream, so it lives in perf-regression.spec.ts which runs against a live backend.)

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };

const PNG_1x1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64'
);

function album(i: number) {
  return {
    Id: `al-${i}`,
    Name: `Album ${i}`,
    Type: 'MusicAlbum',
    Artists: ['Artist'],
    ArtistItems: [],
    ImageTags: {},
  };
}
function trackItem(i: number, fav: boolean) {
  return {
    Id: `tr-${i}`,
    Name: `Track ${i}`,
    Type: 'Audio',
    Artists: ['Artist'],
    ArtistItems: [],
    RunTimeTicks: 1_800_000_000,
    UserData: { IsFavorite: fav, PlaybackPositionTicks: 0, PlayCount: 0, Played: false },
  };
}

// Stateful mock so an optimistic favorite survives the onSettled refetch.
async function mockApi(page: Page): Promise<void> {
  const favorited = new Set<string>();

  await page.route('**/api/**', (route: Route) => {
    const m = route.request().method();
    if (m === 'GET')
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: '{"Items":[],"TotalRecordCount":0}',
      });
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  await page.route('**/Users/AuthenticateByName', (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ AccessToken: VALID_TOKEN, User: USER, SessionInfo: {} }),
    })
  );
  await page.route('**/Users/Me**', (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(USER) })
  );

  await page.route('**/UserFavoriteItems/**', (route: Route) => {
    const id = decodeURIComponent(
      route.request().url().split('/UserFavoriteItems/')[1]!.split('?')[0]!
    );
    if (route.request().method() === 'DELETE') favorited.delete(id);
    else favorited.add(id);
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  await page.route('**/Items**', (route: Route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path.includes('/Images/')) {
      return route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1x1 });
    }
    const params = url.searchParams;
    const start = Number(params.get('StartIndex') ?? '0');
    const limit = Number(params.get('Limit') ?? '50');
    const types = params.get('IncludeItemTypes') ?? '';
    const isFav = params.get('IsFavorite') === 'true';

    let items: unknown[] = [];
    let total = 0;
    if (isFav) {
      const favs = [...favorited];
      total = favs.length;
      items = favs.slice(start, start + limit).map((_id, k) => trackItem(start + k, true));
    } else if (types.includes('MusicAlbum')) {
      total = 120; // > 2 pages so "Load More" is offered
      items = Array.from({ length: Math.max(0, Math.min(limit, total - start)) }, (_, k) =>
        album(start + k)
      );
    } else if (types.includes('MusicArtist')) {
      total = 0;
    } else if (types.includes('Audio')) {
      total = 2;
      items = [trackItem(1, favorited.has('tr-1')), trackItem(2, favorited.has('tr-2'))];
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: items, TotalRecordCount: total }),
    });
  });
}

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill('mock-user');
  await page.getByLabel('Password').fill('mock-pass');
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL('/', { timeout: 15000 });
}

test.describe('Perf regression (mocked backend)', () => {
  test('favorite toggle updates only its row and survives the refetch', async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto('/songs');

    const rows = page.getByTestId('track-row');
    await expect(rows.first()).toBeVisible({ timeout: 15000 });
    await expect(rows).toHaveCount(2);

    const favOf = (i: number) => rows.nth(i).getByRole('button', { name: /favorite/i });
    await expect(favOf(0)).toHaveAttribute('aria-pressed', 'false');
    await expect(favOf(1)).toHaveAttribute('aria-pressed', 'false');

    await favOf(0).click();

    // Optimistic flip on row 0, and it must persist after onSettled invalidate+refetch.
    await expect(favOf(0)).toHaveAttribute('aria-pressed', 'true');
    // Row 1 (a sibling under the same memoized list) must stay untouched.
    await expect(favOf(1)).toHaveAttribute('aria-pressed', 'false');
  });

  test('albums infinite-scroll append keeps the grid correct', async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto('/albums');

    const cards = page.getByTestId('album-card');
    await expect(cards.first()).toBeVisible({ timeout: 15000 });
    const before = await cards.count();
    expect(before).toBeGreaterThan(0);

    const loadMore = page.getByRole('button', { name: 'Load More' });
    await loadMore.scrollIntoViewIfNeeded();
    await loadMore.click();

    await expect(async () => {
      expect(await cards.count()).toBeGreaterThan(before);
    }).toPass({ timeout: 10000 });
  });

  test('favorites tab round-trip stays mounted', async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto('/favorites');

    const favoritesPage = page.getByTestId('favorites-page');
    await expect(favoritesPage).toBeVisible({ timeout: 15000 });

    for (const name of ['Albums', 'Artists', 'Songs']) {
      await page.getByRole('button', { name }).click();
      await expect(favoritesPage).toBeVisible();
    }
  });
});
