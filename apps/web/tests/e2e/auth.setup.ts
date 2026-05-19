import { test as setup, expect } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { TEST_CREDENTIALS } from './helpers/test-config';

const setupDir = path.dirname(fileURLToPath(import.meta.url));
export const STORAGE_STATE_PATH = path.join(setupDir, '.auth', 'user.json');

setup('authenticate', async ({ page }) => {
  const { USERNAME, PASSWORD } = TEST_CREDENTIALS;

  await page.goto('/login');

  const usernameInput = page.getByLabel('Username');
  await expect(usernameInput).toBeVisible({ timeout: 10000 });

  await usernameInput.fill(USERNAME);
  await page.locator('input[type="password"]').fill(PASSWORD);
  await page.getByLabel('Remember me').check();
  await page.getByRole('button', { name: 'Sign In' }).click();

  await page.waitForURL(url => !url.href.includes('/login'), { timeout: 20000 });
  await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible({ timeout: 10000 });

  await page.context().storageState({ path: STORAGE_STATE_PATH });
});
