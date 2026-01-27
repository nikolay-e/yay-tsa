import { test, expect } from './fixtures/login.fixture';
import { TEST_CREDENTIALS } from './helpers/test-config';

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
    await expect(
      loginPage.page.getByRole('heading', { name: /Recently Played|Discover/ })
    ).toBeVisible();
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
    const logoutButton = loginPage.page.getByRole('button', { name: 'Logout' });
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
    await expect(
      loginPage.page.getByRole('heading', { name: /Recently Played|Discover/ })
    ).toBeVisible();
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
    const logoutButton = loginPage.page.getByRole('button', { name: 'Logout' });
    await logoutButton.click();
    await loginPage.expectStillOnLoginPage();

    await expect(loginPage.usernameInput).toHaveValue('');
    await expect(loginPage.passwordInput).toHaveValue('');
  });
});
