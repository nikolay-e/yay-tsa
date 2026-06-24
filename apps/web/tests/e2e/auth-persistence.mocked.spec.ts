import { type Page } from '@playwright/test';
import { test, expect } from './fixtures/console-guard.fixture';
import { stubSse } from './helpers/media-fixtures';

// Backend-free auth-persistence suite. Every /api/* call is stubbed with
// Playwright route mocking, so these run without a live backend (and without
// the login `setup` project) under the `chromium-mocked` project:
//
//   npx playwright test --project=chromium-mocked
//
// They exercise the real frontend persistence/hydration logic end-to-end in a
// browser: storage, restoreSession() against a mocked /Users/Me, the 401 path,
// and PWA-reopen via a fresh browser context.

const VALID_TOKEN = 'mock-access-token';
const USER = { Id: 'user-1', Name: 'mock-user', Policy: { IsAdministrator: false } };

// `extraValidTokens` lets a test accept a token it injected directly into
// storage (simulating a pre-existing/legacy session).
async function mockApi(page: Page, extraValidTokens: string[] = []): Promise<void> {
  const tokenIsValid = (req: { headers(): Record<string, string>; url(): string }): boolean => {
    const auth = req.headers()['authorization'] ?? '';
    const url = req.url();
    const all = [VALID_TOKEN, ...extraValidTokens];
    return all.some(t => auth.includes(t) || url.includes(`api_key=${t}`));
  };

  // Benign catch-all FIRST so the more specific routes below take precedence
  // (Playwright matches the most recently registered route).
  await page.route('**/api/**', route => {
    const method = route.request().method();
    if (method === 'GET') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ Items: [], TotalRecordCount: 0 }),
      });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  // Array-shaped endpoints crash the device/audiobook stores if they get the
  // object-shaped catch-all body.
  await page.route(/\/v1\/me\/(devices|audiobooks)(\?|$)/, route => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  await page.route('**/Users/AuthenticateByName', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ AccessToken: VALID_TOKEN, User: USER, SessionInfo: {} }),
    })
  );

  await page.route(/\/Users\/Me(\?|$)/, route => {
    if (tokenIsValid(route.request())) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(USER),
      });
    }
    return route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      body: JSON.stringify({ status: 401, title: 'Unauthorized' }),
    });
  });
  stubSse(page);
}

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill('mock-user');
  await page.getByLabel('Password').fill('mock-pass');
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL('/', { timeout: 15000 });
}

async function expectLoggedIn(page: Page): Promise<void> {
  await expect(page).toHaveURL('/');
  await expect(page.getByLabel('Username')).toHaveCount(0);
}

async function expectGuest(page: Page): Promise<void> {
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByLabel('Username')).toBeVisible();
}

test.describe('Auth persistence (mocked backend)', () => {
  test('login -> reload -> still logged in', async ({ page }) => {
    await mockApi(page);
    await login(page);

    await page.reload();

    await expectLoggedIn(page);
  });

  test('login -> reopen in a new context (installed PWA reopen) -> still logged in', async ({
    page,
    browser,
  }) => {
    await mockApi(page);
    await login(page);

    // localStorage is the default (Remember me checked) — it rides along in the
    // captured storage state, whereas sessionStorage would not.
    const state = await page.context().storageState();
    const reopened = await browser.newContext({ storageState: state });
    try {
      const page2 = await reopened.newPage();
      await mockApi(page2);
      await page2.goto('/');
      await expectLoggedIn(page2);
    } finally {
      await reopened.close();
    }
  });

  test('login -> clear service-worker caches (version update) -> still logged in', async ({
    page,
  }) => {
    await mockApi(page);
    await login(page);

    await page.evaluate(async () => {
      if ('caches' in window) {
        const keys = await caches.keys();
        await Promise.all(keys.map(k => caches.delete(k)));
      }
    });
    await page.reload();

    await expectLoggedIn(page);
  });

  test('invalid/expired session -> reload -> becomes guest', async ({ page }) => {
    await mockApi(page);
    await login(page);

    // Corrupt the stored token so the mocked /Users/Me answers 401.
    await page.evaluate(() => {
      localStorage.setItem('yaytsa_session_persistent', 'corrupted-token');
      localStorage.setItem('yaytsa_remember_me', 'true');
      document.cookie = 'yay_token=corrupted-token; Path=/; SameSite=Strict';
    });
    await page.reload();

    await expectGuest(page);
  });

  test('explicit logout clears user-scoped storage but keeps the device volume', async ({
    page,
  }) => {
    await mockApi(page);
    await login(page);

    await page.evaluate(() => {
      localStorage.setItem(
        'yaytsa_resume:track-1',
        JSON.stringify({
          itemId: 'track-1',
          positionMs: 90_000,
          runTimeMs: 600_000,
          updatedAt: new Date().toISOString(),
        })
      );
      localStorage.setItem('yaytsa_book_speed_book-1', '1.5');
      localStorage.setItem('yaytsa_audiobook_speed', '1.25');
      localStorage.setItem('yaytsa_sort_prefs', JSON.stringify({ 'library:albums': 'name-asc' }));
      localStorage.setItem('yaytsa_volume', '0.42');
    });

    await page.goto('/settings');
    await page.getByRole('button', { name: /sign out of your account/i }).click();
    await page.getByRole('button', { name: /^sign out$/i }).click();
    await expectGuest(page);

    const stored = await page.evaluate(() => ({
      resume: localStorage.getItem('yaytsa_resume:track-1'),
      bookSpeed: localStorage.getItem('yaytsa_book_speed_book-1'),
      globalSpeed: localStorage.getItem('yaytsa_audiobook_speed'),
      sortPrefs: localStorage.getItem('yaytsa_sort_prefs'),
      volume: localStorage.getItem('yaytsa_volume'),
    }));
    expect(stored.resume).toBeNull();
    expect(stored.bookSpeed).toBeNull();
    expect(stored.globalSpeed).toBeNull();
    expect(stored.sortPrefs).toBeNull();
    expect(stored.volume).toBe('0.42');

    // Explicit sign-out is not an expired session.
    await expect(page.getByTestId('session-expired-banner')).toHaveCount(0);
  });

  test('expired session surfaces a banner on the login page', async ({ page }) => {
    await mockApi(page);
    await login(page);

    await page.evaluate(() => {
      localStorage.setItem('yaytsa_session_persistent', 'corrupted-token');
      localStorage.setItem('yaytsa_remember_me', 'true');
    });
    await page.reload();

    await expectGuest(page);
    await expect(page.getByTestId('session-expired-banner')).toBeVisible();

    // The flag is consumed: a plain revisit of the login page shows no banner.
    await page.reload();
    await expectGuest(page);
    await expect(page.getByTestId('session-expired-banner')).toHaveCount(0);
  });

  test('legacy sessionStorage session is restored and promoted to localStorage', async ({
    page,
    browser,
  }) => {
    const legacyToken = 'legacy-session-token';
    await mockApi(page, [legacyToken]);

    // Seed a legacy session the way an older app version did: raw sessionStorage
    // keys, no scope marker, nothing in localStorage.
    await page.goto('/login');
    await page.evaluate(t => {
      sessionStorage.setItem('yaytsa_session', t);
      sessionStorage.setItem('yaytsa_user_id', 'user-1');
    }, legacyToken);

    await page.goto('/');
    await expectLoggedIn(page);

    // restoreSession() must have promoted it into localStorage.
    const promoted = await page.evaluate(() => localStorage.getItem('yaytsa_session_persistent'));
    expect(promoted).toBe(legacyToken);

    // And it now survives a PWA reopen (new context with the persisted storage).
    const state = await page.context().storageState();
    const reopened = await browser.newContext({ storageState: state });
    try {
      const page2 = await reopened.newPage();
      await mockApi(page2, [legacyToken]);
      await page2.goto('/');
      await expectLoggedIn(page2);
    } finally {
      await reopened.close();
    }
  });
});
