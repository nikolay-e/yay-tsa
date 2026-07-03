import { type Page, type Route, devices } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { TRANSPARENT_PNG, stubSse } from './helpers/media-fixtures';

// Backend-free verification (chromium-mocked project) of the mobile search-bar
// reveal behaviour: the sticky GlobalSearchBar is hidden while scrolling down and
// shown again at the top / when scrolling up. Runs in a mobile viewport (the reveal
// is gated below the `md:` breakpoint; desktop keeps the bar always visible).
//
//   npx playwright test --project=chromium-mocked search-reveal.mocked.spec.ts

test.use({ ...devices['Pixel 7'], hasTouch: true });

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };
const PNG = TRANSPARENT_PNG;

// Enough albums that the Albums grid scrolls well past one mobile viewport.
const ALBUMS = Array.from({ length: 60 }, (_, i) => ({
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
  await page.route('**/api/**', (route: Route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: [], TotalRecordCount: 0 }),
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

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

// The search bar sits at the top of the scroll container; when hidden it is
// translated up out of view, so its rendered top goes negative.
async function searchBarTop(page: Page): Promise<number> {
  const box = await page.getByTestId('global-search-bar').boundingBox();
  return box?.y ?? Number.NaN;
}

test.describe('Mobile search bar reveal (mocked backend)', () => {
  test('hides on scroll down and reappears on scroll up', async ({ page }) => {
    await mockApi(page);
    await login(page);

    await page.goto('/albums');
    await expect(page.getByTestId('albums-content')).toBeVisible({ timeout: 15000 });

    // Visible at the top.
    await expect.poll(() => searchBarTop(page)).toBeGreaterThanOrEqual(0);

    // Scroll the main container down → bar slides up out of view (negative top).
    await page.locator('main').evaluate(el => {
      el.scrollTop = 800;
    });
    await expect.poll(() => searchBarTop(page), { timeout: 5000 }).toBeLessThan(0);

    // Scroll back up → bar reappears.
    await page.locator('main').evaluate(el => {
      el.scrollTop = 200;
    });
    await expect.poll(() => searchBarTop(page), { timeout: 5000 }).toBeGreaterThanOrEqual(0);
  });
});
