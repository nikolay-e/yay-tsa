import { test as base, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

type AuthFixtures = {
  authenticatedPage: Page;
};

export const test = base.extend<AuthFixtures>({
  authenticatedPage: async ({ page }, use) => {
    // Server URL is now configured via environment variables (YAYTSA_SERVER_URL)
    // and loaded from config.json at runtime, not entered by user
    const username = process.env.YAYTSA_TEST_USERNAME || 'test-user';
    const password = process.env.YAYTSA_TEST_PASSWORD || 'test-password';

    await page.goto('/login');

    // Wait for login form (no serverUrl field anymore)
    await page.waitForSelector('input[name="username"]', { timeout: 10000 });

    await page.fill('input[name="username"]', username);
    await page.fill('input[name="password"]', password);

    await page.click('button[type="submit"]');

    await page.waitForURL('/', { timeout: 15000 });
    await expect(page.getByRole('heading', { name: 'Recent Albums' })).toBeVisible({ timeout: 10000 });

    await use(page);
  },
});

export { expect };
