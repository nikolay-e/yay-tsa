import { test as base, expect } from '@playwright/test';
import { test as authTest } from './fixtures/auth.fixture';
import { NAVIGATION_TEST_IDS } from './helpers/test-ids';

base.describe('Navigation — Unauthenticated', () => {
  base('should redirect to login when accessing protected route without auth', async ({ page }) => {
    await page.goto('/albums');

    await expect(page).toHaveURL('/login', { timeout: 10000 });
    await expect(page.getByLabel('Username')).toBeVisible({ timeout: 5000 });
  });

  base('should redirect to login for deep link to non-existent route', async ({ page }) => {
    await page.goto('/this-page-does-not-exist-at-all');

    await expect(page).toHaveURL('/login', { timeout: 10000 });
    await expect(page.getByLabel('Username')).toBeVisible({ timeout: 5000 });
  });
});

authTest.describe('Navigation — Authenticated', () => {
  authTest('should handle deep link to /albums', async ({ authenticatedPage }) => {
    await authenticatedPage.goto('/albums');

    await expect(authenticatedPage).toHaveURL('/albums');
    await expect(
      authenticatedPage
        .getByTestId('album-card')
        .first()
        .or(authenticatedPage.getByText('No albums found'))
    ).toBeVisible({ timeout: 10000 });
  });

  authTest('should handle deep link to /artists', async ({ authenticatedPage }) => {
    await authenticatedPage.goto('/artists');

    await expect(authenticatedPage).toHaveURL('/artists');
    await expect(authenticatedPage.getByRole('heading', { name: 'Artists' })).toBeVisible({
      timeout: 10000,
    });
  });

  authTest('should show not found for non-existent route', async ({ authenticatedPage }) => {
    await authenticatedPage.goto('/this-page-does-not-exist');

    await expect(authenticatedPage.getByText('Page not found')).toBeVisible({ timeout: 10000 });
  });

  authTest('should handle browser back and forward buttons', async ({ authenticatedPage }) => {
    const page = authenticatedPage;

    await expect(page).toHaveURL('/');

    const sidebar = page.getByTestId(NAVIGATION_TEST_IDS.SIDEBAR);
    const bottomTab = page.getByTestId(NAVIGATION_TEST_IDS.BOTTOM_TAB_BAR);

    const clickNav = async (testId: string) => {
      if (await sidebar.isVisible()) {
        await sidebar.getByTestId(testId).click();
      } else {
        await bottomTab.getByTestId(testId).click();
      }
    };

    await clickNav(NAVIGATION_TEST_IDS.NAV_ALBUMS);
    await expect(page).toHaveURL('/albums');
    await expect(page.getByTestId('album-card').first()).toBeVisible({ timeout: 10000 });

    await clickNav(NAVIGATION_TEST_IDS.NAV_ARTISTS);
    await expect(page).toHaveURL('/artists');
    await expect(page.getByRole('heading', { name: 'Artists' })).toBeVisible({ timeout: 10000 });

    await page.goBack();
    await expect(page).toHaveURL('/albums');

    await page.goBack();
    await expect(page).toHaveURL('/');

    await page.goForward();
    await expect(page).toHaveURL('/albums');
  });
});
