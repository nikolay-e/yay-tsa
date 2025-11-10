import { test, expect } from '@playwright/test';
import { LoginPage } from './pages/LoginPage';

test.describe('Authentication Flow', () => {
  let loginPage: LoginPage;

  const serverUrl = process.env.YAYTSA_SERVER_URL || 'http://localhost:8096';
  const username = process.env.YAYTSA_TEST_USERNAME || 'test-user';
  const password = process.env.YAYTSA_TEST_PASSWORD || 'test-password';

  test.beforeEach(async ({ page }) => {
    loginPage = new LoginPage(page);
  });

  test('should display login form', async () => {
    await loginPage.goto();

    await expect(loginPage.serverUrlInput).toBeVisible();
    await expect(loginPage.usernameInput).toBeVisible();
    await expect(loginPage.passwordInput).toBeVisible();
    await expect(loginPage.loginButton).toBeVisible();
  });

  test('should successfully login with valid credentials', async () => {
    await loginPage.goto();
    await loginPage.login(serverUrl, username, password);
    await loginPage.waitForRedirectToHome();

    await expect(loginPage.page).toHaveURL('/');
    await expect(loginPage.page.locator('text=Albums')).toBeVisible();
  });

  test('should show error with invalid credentials', async ({ page }) => {
    await loginPage.goto();
    await loginPage.login(serverUrl, 'invalid-user', 'wrong-password');

    await expect(page).toHaveURL('/login');
  });

  test('should show error with empty credentials', async ({ page }) => {
    await loginPage.goto();

    await loginPage.loginButton.click();

    await expect(page).toHaveURL('/login');
  });

  test('should logout successfully', async ({ page }) => {
    await loginPage.goto();
    await loginPage.login(serverUrl, username, password);
    await loginPage.waitForRedirectToHome();

    const logoutButton = page.locator('[data-testid="logout-button"]');
    if (await logoutButton.isVisible()) {
      await logoutButton.click();
      await expect(page).toHaveURL('/login');
      await expect(loginPage.serverUrlInput).toBeVisible();
    }
  });

  test('should persist session after page reload', async ({ page }) => {
    await loginPage.goto();
    await loginPage.login(serverUrl, username, password);
    await loginPage.waitForRedirectToHome();

    await page.reload();

    await expect(page).toHaveURL('/');
    await expect(page.locator('text=Albums')).toBeVisible();
  });

  test('should redirect to login if not authenticated', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveURL('/login');
    await expect(loginPage.serverUrlInput).toBeVisible();
  });

  test('should validate server URL format', async ({ page }) => {
    await loginPage.goto();

    await loginPage.serverUrlInput.fill('not-a-valid-url');
    await loginPage.usernameInput.fill(username);
    await loginPage.passwordInput.fill(password);
    await loginPage.loginButton.click();

    await expect(page).toHaveURL('/login');
  });

  test('should clear form on logout', async ({ page }) => {
    await loginPage.goto();
    await loginPage.login(serverUrl, username, password);
    await loginPage.waitForRedirectToHome();

    const logoutButton = page.locator('[data-testid="logout-button"]');
    if (await logoutButton.isVisible()) {
      await logoutButton.click();
      await expect(page).toHaveURL('/login');

      await expect(loginPage.usernameInput).toHaveValue('');
      await expect(loginPage.passwordInput).toHaveValue('');
    }
  });
});
