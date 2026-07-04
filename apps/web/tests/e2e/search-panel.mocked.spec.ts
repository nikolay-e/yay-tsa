import { type Page, type Route } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { TRANSPARENT_PNG, stubSse } from './helpers/media-fixtures';

// Backend-free verification (chromium-mocked project) of the global search panel: it stays out of
// view until the header magnifier is clicked, then slides down from the top; Escape dismisses it.

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };
const PNG = TRANSPARENT_PNG;

const ALBUMS = Array.from({ length: 12 }, (_, i) => ({
  Id: `album-${i}`,
  Name: `Album ${i}`,
  Type: 'MusicAlbum',
  ServerId: 'server-1',
  Artists: ['Tester'],
  ProductionYear: 2020,
  ImageTags: {},
  UserData: { IsFavorite: false, PlaybackPositionTicks: 0, PlayCount: 0, Played: false },
}));

async function mockApi(page: Page): Promise<void> {
  await page.route('**/api/**', (route: Route) =>
    route.request().method() === 'GET'
      ? route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ Items: [], TotalRecordCount: 0 }),
        })
      : route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  );
  await page.route(/\/Items(\?|$)/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: ALBUMS, TotalRecordCount: ALBUMS.length }),
    })
  );
  await page.route(/IsFavorite=true/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ Items: [], TotalRecordCount: 0 }),
    })
  );
  await page.route(/\/Images\//, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: PNG })
  );
  await page.route(/\/Users\/AuthenticateByName/, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ AccessToken: VALID_TOKEN, User: USER, SessionInfo: {} }),
    })
  );
  await page.route(/\/Users\/Me(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(USER) })
  );
  await page.route(/\/v1\/me\/devices(\?|$)/, (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  );
  stubSse(page);
}

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill('mock-user');
  await page.getByLabel('Password').fill('mock-pass');
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL('/', { timeout: 15000 });
}

// Rendered top of the panel: off-screen (negative) when closed, 0 when it has slid down.
async function panelTop(page: Page): Promise<number> {
  const box = await page.getByTestId('global-search-bar').boundingBox();
  return box?.y ?? Number.NaN;
}

test.describe('Global search panel (mocked backend)', () => {
  test('opens from the header magnifier and dismisses via close', async ({ page }) => {
    await mockApi(page);
    await login(page);

    await page.goto('/albums');
    await expect(page.getByTestId('albums-content')).toBeVisible({ timeout: 15000 });

    // Closed: panel sits above the viewport.
    await expect.poll(() => panelTop(page)).toBeLessThan(0);

    // Click the header magnifier → panel slides in and the field takes focus.
    await page.getByTestId('open-search').click();
    await expect(page.getByTestId('global-search-input')).toBeFocused();
    await expect.poll(() => panelTop(page), { timeout: 5000 }).toBeGreaterThanOrEqual(0);

    // Typing drives the /search route; wait for the results page to settle before dismissing.
    await page.getByTestId('global-search-input').fill('test');
    await expect(page).toHaveURL(/\/search\?q=test/);
    await expect(page.locator('[data-testid^="search-section-"]').first()).toBeVisible({
      timeout: 15000,
    });

    // The close button leaves search and collapses the panel.
    await page.getByRole('button', { name: 'Close search' }).click();
    await expect(page).toHaveURL('/');
    await expect.poll(() => panelTop(page), { timeout: 5000 }).toBeLessThan(0);
  });
});
