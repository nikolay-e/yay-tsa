import { test, expect } from './fixtures/accessibility.fixture';

test.describe('Accessibility', () => {
  test('login page has no accessibility violations', async ({ page, checkAccessibility }) => {
    await page.goto('/login');
    await expect(page.getByLabel('Username')).toBeVisible();

    await checkAccessibility(page);
  });

  test('home page has no accessibility violations', async ({
    authenticatedPage,
    checkAccessibility,
  }) => {
    await checkAccessibility(authenticatedPage);
  });

  test('albums page has no accessibility violations', async ({
    authenticatedPage,
    checkAccessibility,
  }) => {
    await authenticatedPage.goto('/albums');
    await expect(authenticatedPage.getByRole('heading', { name: 'Albums' })).toBeVisible();

    await checkAccessibility(authenticatedPage);
  });

  test('artists page has no accessibility violations', async ({
    authenticatedPage,
    checkAccessibility,
  }) => {
    await authenticatedPage.goto('/artists');
    await expect(authenticatedPage.getByRole('heading', { name: 'Artists' })).toBeVisible();

    await checkAccessibility(authenticatedPage);
  });

  test('songs page has no accessibility violations', async ({
    authenticatedPage,
    checkAccessibility,
  }) => {
    await authenticatedPage.goto('/songs');
    await expect(authenticatedPage.getByRole('heading', { name: 'Songs' })).toBeVisible();

    await checkAccessibility(authenticatedPage);
  });

  test('settings page has no accessibility violations', async ({
    authenticatedPage,
    checkAccessibility,
  }) => {
    await authenticatedPage.goto('/settings');
    await expect(authenticatedPage.getByRole('heading', { name: 'Settings' })).toBeVisible();

    await checkAccessibility(authenticatedPage);
  });
});
