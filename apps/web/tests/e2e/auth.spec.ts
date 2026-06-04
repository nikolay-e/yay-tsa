import { test, expect } from './fixtures/login.fixture';
import { TEST_CREDENTIALS } from './helpers/test-config';

// Auth flow tests must start from an unauthenticated state to exercise the login UI.
test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Authentication Flow', () => {
  const { USERNAME: username, PASSWORD: password } = TEST_CREDENTIALS;

  test('should display login form', async ({ loginPage }) => {
    await loginPage.goto();

    await expect(loginPage.usernameInput).toBeVisible();
    await expect(loginPage.passwordInput).toBeVisible();
    await expect(loginPage.loginButton).toBeVisible();
  });

  test('should successfully login with valid credentials', async ({ loginPage }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    await expect(loginPage.page).toHaveURL('/');
    await expect(loginPage.page.getByRole('heading', { level: 1 }).first()).toBeVisible();
  });

  test('should show error with invalid credentials', async ({ loginPage }) => {
    await loginPage.goto();
    await loginPage.login('invalid-user', 'wrong-password');

    await loginPage.expectStillOnLoginPage();
  });

  test('should show error with empty credentials', async ({ loginPage }) => {
    await loginPage.goto();

    await loginPage.loginButton.click();

    await loginPage.expectStillOnLoginPage();
  });

  test('should logout successfully', async ({ loginPage }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    await loginPage.page.goto('/settings');
    const logoutButton = loginPage.page.getByRole('button', { name: /Logout/i });
    await expect(logoutButton).toBeVisible({ timeout: 10000 });
    await logoutButton.click();
    await loginPage.expectStillOnLoginPage();
    await expect(loginPage.usernameInput).toBeVisible();
  });

  test('should persist session after page reload', async ({ loginPage }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    await loginPage.reload();

    await expect(loginPage.page).toHaveURL('/');
    await expect(loginPage.page.getByRole('heading', { level: 1 }).first()).toBeVisible();
  });

  test('should stay logged in when reopened in a new context (installed PWA reopen)', async ({
    loginPage,
    browser,
  }) => {
    await loginPage.goto();
    // Remember me defaults to ON, so the session lands in localStorage.
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    // A fresh browser context that inherits the persisted storage simulates
    // closing and reopening the installed PWA (sessionStorage would be gone,
    // localStorage survives).
    const state = await loginPage.page.context().storageState();
    const reopened = await browser.newContext({ storageState: state });
    try {
      const page = await reopened.newPage();
      await page.goto('/');
      await expect(page).toHaveURL('/');
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible({ timeout: 10000 });
    } finally {
      await reopened.close();
    }
  });

  test('should stay logged in after a frontend version update invalidates caches', async ({
    loginPage,
  }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    // Simulate a deploy: the service worker drops its old asset caches. Auth
    // lives in localStorage, not the cache, so the user must remain signed in.
    await loginPage.page.evaluate(async () => {
      if ('caches' in window) {
        const keys = await caches.keys();
        await Promise.all(keys.map(key => caches.delete(key)));
      }
    });
    await loginPage.reload();

    await expect(loginPage.page).toHaveURL('/');
    await expect(loginPage.page.getByRole('heading', { level: 1 }).first()).toBeVisible({
      timeout: 10000,
    });
  });

  test('should become guest on reload when the stored session is invalid', async ({
    loginPage,
  }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    // Corrupt the persisted token so the backend rejects it with 401.
    await loginPage.page.evaluate(() => {
      localStorage.setItem('yaytsa_session_persistent', 'invalid-token-xyz');
      localStorage.setItem('yaytsa_remember_me', 'true');
      sessionStorage.setItem('yaytsa_session', 'invalid-token-xyz');
      document.cookie = 'yay_token=invalid-token-xyz; Path=/; SameSite=Strict';
    });
    await loginPage.reload();

    await loginPage.expectStillOnLoginPage();
    await expect(loginPage.usernameInput).toBeVisible();
  });

  test('should redirect to login if not authenticated', async ({ loginPage }) => {
    await loginPage.navigateTo('/');

    await loginPage.expectStillOnLoginPage();
    await expect(loginPage.usernameInput).toBeVisible();
  });

  test('should clear form on logout', async ({ loginPage }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    await loginPage.page.goto('/settings');
    const logoutButton = loginPage.page.getByRole('button', { name: /Logout/i });
    await expect(logoutButton).toBeVisible({ timeout: 10000 });
    await logoutButton.click();
    await loginPage.expectStillOnLoginPage();

    await expect(loginPage.usernameInput).toHaveValue('');
    await expect(loginPage.passwordInput).toHaveValue('');
  });
});
