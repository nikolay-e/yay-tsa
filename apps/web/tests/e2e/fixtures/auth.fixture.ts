import { test as base, expect, type Page } from '@playwright/test';
import { TEST_CREDENTIALS } from '../helpers/test-config';

type AuthFixtures = {
  authenticatedPage: Page;
};

async function loginWithRetry(page: Page, username: string, password: string, maxRetries = 2) {
  const consoleLogs: string[] = [];
  const networkErrors: string[] = [];

  page.on('console', msg => {
    consoleLogs.push(`[${msg.type()}] ${msg.text()}`);
  });

  page.on('requestfailed', request => {
    networkErrors.push(`${request.method()} ${request.url()} - ${request.failure()?.errorText}`);
  });

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    consoleLogs.length = 0;
    networkErrors.length = 0;

    await page.goto('/login');

    const usernameInput = page.getByLabel('Username');
    await expect(usernameInput).toBeVisible({ timeout: 5000 });

    await usernameInput.fill(username);
    await page.locator('input[type="password"]').fill(password);

    await page.getByRole('button', { name: 'Sign In' }).click();

    // Wait for either: navigation away from login OR error visible
    const result = await Promise.race([
      page
        .waitForURL(url => !url.includes('login'), { timeout: 15000 })
        .then(() => 'navigated' as const),
      page
        .locator('[role="alert"]')
        .waitFor({ state: 'visible', timeout: 15000 })
        .then(() => 'error' as const),
      new Promise<'timeout'>(resolve => setTimeout(() => resolve('timeout'), 15000)),
    ]);

    if (result === 'navigated') {
      await page
        .getByRole('heading', { level: 1 })
        .first()
        .waitFor({ state: 'visible', timeout: 5000 });
      return;
    }

    if (result === 'error') {
      const errorText = await page
        .locator('[role="alert"]')
        .textContent()
        .catch(() => null);
      throw new Error(`Login rejected by server: ${errorText || 'unknown error'}`);
    }

    const currentUrl = page.url();
    const pageContent = await page
      .locator('body')
      .textContent()
      .catch(() => 'N/A');

    if (attempt < maxRetries) {
      console.log(`Login attempt ${attempt} result: ${result}`);
      console.log(`  URL: ${currentUrl}`);
      console.log(`  Console: ${consoleLogs.slice(-5).join('\n    ')}`);
      console.log(`  Network errors: ${networkErrors.join(', ') || 'none'}`);
      await page.waitForTimeout(1000);
    } else {
      const errorText = await page
        .locator('[role="alert"]')
        .textContent()
        .catch(() => null);
      throw new Error(
        `Login failed after ${maxRetries} attempts.\n` +
          `Last result: ${result}\n` +
          `URL: ${currentUrl}\n` +
          `Error on page: ${errorText || 'none'}\n` +
          `Console (last 10):\n  ${consoleLogs.slice(-10).join('\n  ')}\n` +
          `Network errors: ${networkErrors.join(', ') || 'none'}\n` +
          `Page content snippet: ${pageContent?.substring(0, 200) || 'N/A'}`
      );
    }
  }
}

export const test = base.extend<AuthFixtures>({
  authenticatedPage: async ({ page }, use) => {
    const { USERNAME: username, PASSWORD: password } = TEST_CREDENTIALS;

    await loginWithRetry(page, username, password);

    // Wait for navigation to home page
    await expect(page).toHaveURL('/', { timeout: 5000 });

    // Verify home page content loaded
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible({ timeout: 5000 });

    await use(page);

    // Cleanup
    await page.evaluate(() => {
      const audio = document.querySelector('audio');
      if (audio) {
        audio.pause();
        audio.src = '';
      }
      sessionStorage.clear();
    });
  },
});

export { expect } from '@playwright/test';
