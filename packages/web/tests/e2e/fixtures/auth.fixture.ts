import { test as base, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { TEST_CREDENTIALS } from '../helpers/test-config';

type AuthFixtures = {
  authenticatedPage: Page;
};

export const test = base.extend<AuthFixtures>({
  authenticatedPage: async ({ page }, use) => {
    const { USERNAME: username, PASSWORD: password } = TEST_CREDENTIALS;

    await page.goto('/login');

    const usernameInput = page.getByLabel('Username');
    await expect(usernameInput).toBeVisible({ timeout: 10000 });

    await usernameInput.fill(username);
    await page.getByLabel('Password').fill(password);

    await page.getByRole('button', { name: 'Sign In' }).click();

    await page.waitForURL('/', { timeout: 15000 });
    // Home page shows either 'Recently Played' or 'Discover' depending on user history
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible({ timeout: 10000 });

    await use(page);

    // Cleanup: clear session storage and stop any playing audio
    await page.evaluate(() => {
      // Stop any playing audio
      const audio = document.querySelector('audio') as HTMLAudioElement | null;
      if (audio) {
        audio.pause();
        audio.src = '';
      }
      // Clear session storage
      sessionStorage.clear();
    });
  },
});

export { expect };
