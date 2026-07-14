import { expect, type Page } from '@playwright/test';
import { test as base } from './console-guard.fixture';

type AuthFixtures = {
  authenticatedPage: Page;
};

export const test = base.extend<AuthFixtures>({
  authenticatedPage: async ({ page }, use) => {
    await page.goto('/');
    await expect(page).toHaveURL('/', { timeout: 10000 });
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible({ timeout: 10000 });

    await use(page);

    await page.evaluate(() => {
      const audio = document.querySelector('audio');
      if (audio) {
        audio.pause();
        audio.src = '';
      }
    });
  },
});

export { expect } from '@playwright/test';
