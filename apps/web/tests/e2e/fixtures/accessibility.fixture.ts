import { test as base, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { TEST_CREDENTIALS } from '../helpers/test-config';

type AccessibilityFixtures = {
  authenticatedPage: Page;
  checkAccessibility: (page: Page, options?: { excludeRules?: string[] }) => Promise<void>;
};

async function loginWithRetry(page: Page, username: string, password: string, maxRetries = 2) {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    await page.goto('/login');

    const usernameInput = page.getByLabel('Username');
    await expect(usernameInput).toBeVisible({ timeout: 5000 });

    await usernameInput.fill(username);
    await page.locator('input[type="password"]').fill(password);
    await page.getByRole('button', { name: 'Sign In' }).click();

    const result = await Promise.race([
      page
        .waitForFunction(() => sessionStorage.getItem('yaytsa_session') !== null, {
          timeout: 15000,
        })
        .then(() => 'success' as const),
      page.waitForURL('/', { timeout: 15000 }).then(() => 'navigated' as const),
      new Promise<'timeout'>(resolve => setTimeout(() => resolve('timeout'), 15000)),
    ]);

    if (result === 'success' || result === 'navigated') {
      const hasSession = await page.evaluate(
        () => sessionStorage.getItem('yaytsa_session') !== null
      );
      if (hasSession) return;
    }

    if (attempt === maxRetries) {
      throw new Error(`Login failed after ${maxRetries} attempts`);
    }
    await page.waitForTimeout(1000);
  }
}

export const test = base.extend<AccessibilityFixtures>({
  authenticatedPage: async ({ page }, use) => {
    const { USERNAME: username, PASSWORD: password } = TEST_CREDENTIALS;
    await loginWithRetry(page, username, password);
    await expect(page).toHaveURL('/', { timeout: 5000 });
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible({ timeout: 5000 });
    await use(page);

    await page.evaluate(() => {
      const audio = document.querySelector('audio') as HTMLAudioElement | null;
      if (audio) {
        audio.pause();
        audio.src = '';
      }
      sessionStorage.clear();
    });
  },

  checkAccessibility: async (_fixtures, use) => {
    const checker = async (page: Page, options?: { excludeRules?: string[] }) => {
      let builder = new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']);

      if (options?.excludeRules) {
        builder = builder.disableRules(options.excludeRules);
      }

      const results = await builder.analyze();

      if (results.violations.length > 0) {
        const violationsSummary = results.violations
          .map(v => `- ${v.id}: ${v.description} (${v.nodes.length} occurrences)`)
          .join('\n');
        throw new Error(`Accessibility violations found:\n${violationsSummary}`);
      }
    };

    await use(checker);
  },
});

export { expect };
