import { test, expect } from '@playwright/test';
import { LoginPage } from './pages/LoginPage';

test.describe('Authentication Flow', () => {
  let loginPage: LoginPage;

  const username = process.env.YAYTSA_TEST_USERNAME || 'test-user';
  const password = process.env.YAYTSA_TEST_PASSWORD || 'test-password';

  test.beforeEach(async ({ page }) => {
    loginPage = new LoginPage(page);
  });

  test('should display login form', async () => {
    await loginPage.goto();

    await expect(loginPage.usernameInput).toBeVisible();
    await expect(loginPage.passwordInput).toBeVisible();
    await expect(loginPage.loginButton).toBeVisible();
  });

  test('should successfully login with valid credentials', async () => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    await expect(loginPage.page).toHaveURL('/');
    await expect(loginPage.page.getByRole('heading', { name: /Recently Played|Discover/ })).toBeVisible();
  });

  test('should show error with invalid credentials', async ({ page }) => {
    await loginPage.goto();
    await loginPage.login('invalid-user', 'wrong-password');

    await expect(page).toHaveURL('/login');
  });

  test('should show error with empty credentials', async ({ page }) => {
    await loginPage.goto();

    await loginPage.loginButton.click();

    await expect(page).toHaveURL('/login');
  });

  test('should logout successfully', async ({ page }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    const logoutButton = page.getByRole('button', { name: 'Logout' }).first();
    await logoutButton.click();
    await expect(page).toHaveURL('/login');
    await expect(loginPage.usernameInput).toBeVisible();
  });

  test('should persist session after page reload', async ({ page }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    await page.reload();

    await expect(page).toHaveURL('/');
    await expect(page.getByRole('heading', { name: /Recently Played|Discover/ })).toBeVisible();
  });

  test('should redirect to login if not authenticated', async ({ page }) => {
    await page.goto('/');

    await expect(page).toHaveURL('/login');
    await expect(loginPage.usernameInput).toBeVisible();
  });

  test('should clear form on logout', async ({ page }) => {
    await loginPage.goto();
    await loginPage.login(username, password);
    await loginPage.waitForRedirectToRecent();

    const logoutButton = page.getByRole('button', { name: 'Logout' }).first();
    await logoutButton.click();
    await expect(page).toHaveURL('/login');

    await expect(loginPage.usernameInput).toHaveValue('');
    await expect(loginPage.passwordInput).toHaveValue('');
  });
});
