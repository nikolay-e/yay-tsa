import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';

export async function waitForPlayerToLoad(page: Page): Promise<void> {
  await expect(page.locator('[data-testid="player-bar"]')).toBeVisible({ timeout: 10000 });
}

export async function waitForAlbumsToLoad(page: Page): Promise<void> {
  await expect(page.locator('[data-testid="album-card"]').first()).toBeVisible({ timeout: 10000 });
}

export async function getFirstAlbum(page: Page): Promise<Locator> {
  await waitForAlbumsToLoad(page);
  return page.locator('[data-testid="album-card"]').first();
}

export async function clickPlayButton(page: Page): Promise<void> {
  await page.click('[data-testid="play-button"]');
  await waitForPlayerToLoad(page);
}

export async function waitForTrackToPlay(page: Page): Promise<void> {
  await expect(page.locator('[data-testid="play-pause-button"][aria-label*="Pause"]')).toBeVisible({
    timeout: 10000,
  });
}

export async function getCurrentTrackTitle(page: Page): Promise<string> {
  const titleElement = await page.locator('[data-testid="current-track-title"]').textContent();
  return titleElement || '';
}

export async function clearSearch(page: Page): Promise<void> {
  const searchInput = page.locator('input[type="search"]');
  await searchInput.clear();
}

export async function performSearch(page: Page, query: string): Promise<void> {
  const searchInput = page.locator('input[type="search"]');
  await searchInput.fill(query);
  await page.waitForTimeout(500);
}

export async function assertPlayerState(
  page: Page,
  state: 'playing' | 'paused'
): Promise<void> {
  const label = state === 'playing' ? 'Pause' : 'Play';
  await expect(
    page.locator(`[data-testid="play-pause-button"][aria-label*="${label}"]`)
  ).toBeVisible();
}

export async function waitForNetworkIdle(page: Page, timeout: number = 2000): Promise<void> {
  await page.waitForLoadState('networkidle', { timeout });
}

export async function takeScreenshotOnFailure(
  page: Page,
  testInfo: any,
  name: string
): Promise<void> {
  if (testInfo.status !== testInfo.expectedStatus) {
    await page.screenshot({ path: `test-results/${name}-${Date.now()}.png`, fullPage: true });
  }
}
